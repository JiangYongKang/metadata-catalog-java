package com.github.highcumontoa.metadatacatalogjava.service;

import com.github.highcumontoa.metadatacatalogjava.dto.ImpactResult;
import com.github.highcumontoa.metadatacatalogjava.dto.LineageRequest;
import com.github.highcumontoa.metadatacatalogjava.dto.LineageResult;
import com.github.highcumontoa.metadatacatalogjava.model.ActionType;
import com.github.highcumontoa.metadatacatalogjava.model.Decision;
import com.github.highcumontoa.metadatacatalogjava.model.EntityDef;
import com.github.highcumontoa.metadatacatalogjava.model.LineageEdge;
import com.github.highcumontoa.metadatacatalogjava.model.RejectReason;
import com.github.highcumontoa.metadatacatalogjava.store.LocalMetadataStore;
import com.github.highcumontoa.metadatacatalogjava.util.Names;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.Lock;

/**
 * 血缘登记、环检测与多跳影响查询服务。
 *
 * <p>写入口一致性：端点实体名一律经 {@link Names#canonical(String)} 归一，
 * 大小写/首尾空白差异不会各算一套血缘；端点存在性、废弃、权限检查与登记/变更/废弃/移除
 * 各入口使用同一套顺序（存在 -&gt; 自引用 -&gt; 废弃 -&gt; 权限 -&gt; 业务规则）。</p>
 *
 * <p>规则：</p>
 * <ol>
 *   <li>上下游实体必须均已登记，否则 UNKNOWN_ENTITY；</li>
 *   <li>自引用（upstream == downstream）以 SELF_REFERENCE 拒绝；</li>
 *   <li>任一端点实体已废弃 -> ENTITY_DEPRECATED；操作者对任一端点无写权限 -> PERMISSION_DENIED；</li>
 *   <li>若 downstream 已能沿既有血缘到达 upstream，则新增 upstream-&gt;downstream 成环，
 *       以 CYCLE_DETECTED 拒绝，并给出可达路径作为依据；</li>
 *   <li>相同的边重复登记结论为 NO_CHANGE，不重复计数；</li>
 *   <li>加锁顺序全局固定：按规范名升序获取两端实体锁，再获取血缘锁，
 *       环检测与写入在同一临界区完成，与实体移除路径不会互相等待、结论确定。</li>
 * </ol>
 */
@Service
public class LineageService {

    private static final Logger log = LoggerFactory.getLogger(LineageService.class);

    private final LocalMetadataStore store;
    private final PermissionService permissionService;

    public LineageService(LocalMetadataStore store, PermissionService permissionService) {
        this.store = store;
        this.permissionService = permissionService;
    }

    public LineageResult registerLineage(LineageRequest request) {
        log.info("[LINEAGE] 输入 source={}, upstream={}, downstream={}",
                request == null ? null : request.source(),
                request == null ? null : request.upstream(),
                request == null ? null : request.downstream());

        if (request == null || isBlank(request.upstream()) || isBlank(request.downstream())) {
            return reject(request, RejectReason.INVALID_PAYLOAD,
                    "upstream 与 downstream 均为必填项");
        }
        String source = isBlank(request.source()) ? "anonymous" : request.source().trim();
        String upstream = Names.canonical(request.upstream());
        String downstream = Names.canonical(request.downstream());

        // 固定加锁顺序：端点实体锁（规范名升序）-> 血缘锁，避免与移除路径死锁；
        // 自引用时两端点同名，只取一把锁
        Set<String> endpoints = new TreeSet<>();
        endpoints.add(upstream);
        endpoints.add(downstream);
        List<Lock> entityLocks = endpoints.stream().map(store::lockFor).toList();
        entityLocks.forEach(Lock::lock);
        store.lineageLock().lock();
        try {
            EntityDef upEntity = store.getEntity(upstream);
            EntityDef downEntity = store.getEntity(downstream);
            if (upEntity == null || downEntity == null) {
                return reject(request, RejectReason.UNKNOWN_ENTITY,
                        "上下游实体必须均已登记：upstream=" + upstream + ", downstream=" + downstream,
                        upstream, downstream, source);
            }
            if (upstream.equals(downstream)) {
                return reject(request, RejectReason.SELF_REFERENCE,
                        "拒绝自引用血缘：" + upstream + " -> " + downstream,
                        upstream, downstream, source);
            }
            if (upEntity.isDeprecated() || downEntity.isDeprecated()) {
                return reject(request, RejectReason.ENTITY_DEPRECATED,
                        "血缘端点存在已废弃实体（upstream=" + upstream + " deprecated=" + upEntity.isDeprecated()
                                + ", downstream=" + downstream + " deprecated=" + downEntity.isDeprecated()
                                + "），拒绝一切写操作",
                        upstream, downstream, source);
            }
            if (!permissionService.canWrite(downstream, source)
                    || !permissionService.canWrite(upstream, source)) {
                return reject(request, RejectReason.PERMISSION_DENIED,
                        "来源/操作者 '" + source + "' 对血缘涉及实体无写权限",
                        upstream, downstream, source);
            }
            if (store.lineageExists(upstream, downstream)) {
                LineageResult result = new LineageResult(upstream, downstream, Decision.NO_CHANGE,
                        RejectReason.NONE, "该血缘此前已登记，幂等返回，不重复计数");
                store.appendAudit(ActionType.REGISTER_LINEAGE, source,
                        upstream + "->" + downstream, Decision.NO_CHANGE, RejectReason.NONE,
                        "重复登记血缘，忽略");
                log.info("[LINEAGE] 结论 {}->{} decision=NO_CHANGE 依据=边已存在", upstream, downstream);
                return result;
            }
            List<String> existingPath = findPath(downstream, upstream);
            if (existingPath != null) {
                String msg = "登记 " + upstream + "->" + downstream + " 将形成环；既有可达路径："
                        + String.join(" -> ", existingPath);
                LineageResult result = new LineageResult(upstream, downstream, Decision.REJECTED,
                        RejectReason.CYCLE_DETECTED, msg);
                store.appendAudit(ActionType.REGISTER_LINEAGE, source,
                        upstream + "->" + downstream, Decision.REJECTED, RejectReason.CYCLE_DETECTED, msg);
                log.info("[LINEAGE] 结论 {}->{} decision=REJECTED reason=CYCLE_DETECTED 依据={}",
                        upstream, downstream, msg);
                return result;
            }

            store.addLineage(new LineageEdge(upstream, downstream, source));
            LineageResult result = new LineageResult(upstream, downstream, Decision.ACCEPTED,
                    RejectReason.NONE, "血缘登记成功");
            store.appendAudit(ActionType.REGISTER_LINEAGE, source,
                    upstream + "->" + downstream, Decision.ACCEPTED, RejectReason.NONE,
                    "新增血缘 " + upstream + "->" + downstream);
            log.info("[LINEAGE] 结论 {}->{} decision=ACCEPTED 依据=新边且不构成环", upstream, downstream);
            return result;
        } finally {
            store.lineageLock().unlock();
            entityLocks.forEach(Lock::unlock);
        }
    }

    /** 下游多跳影响：BFS 按跳数分层；若遍历中遇到已访问层级之外的回边，则标记 cycleMarked。 */
    public ImpactResult downstreamImpact(String qualifiedName) {
        String origin = Names.canonical(qualifiedName);
        log.info("[IMPACT] 输入 origin={}", origin);
        if (origin == null || store.getEntity(origin) == null) {
            throw new IllegalArgumentException("实体不存在或名称为空：" + qualifiedName);
        }
        Map<Integer, List<String>> hopsMap = new LinkedHashMap<>();
        Set<String> visited = new LinkedHashSet<>();
        visited.add(origin);
        boolean[] cycle = {false};

        Deque<String> queue = new ArrayDeque<>();
        Deque<Integer> distQueue = new ArrayDeque<>();
        queue.add(origin);
        distQueue.add(0);

        while (!queue.isEmpty()) {
            String node = queue.poll();
            int dist = distQueue.poll();
            for (String next : store.downstreamOf(node).stream().sorted().toList()) {
                if (visited.contains(next)) {
                    // 指向已访问节点（含回指 origin）说明图中存在环
                    cycle[0] = true;
                    continue;
                }
                visited.add(next);
                int nextDist = dist + 1;
                hopsMap.computeIfAbsent(nextDist, k -> new ArrayList<>()).add(next);
                queue.add(next);
                distQueue.add(nextDist);
            }
        }

        List<ImpactResult.Hop> hops = new ArrayList<>();
        hopsMap.forEach((distance, entities) -> hops.add(new ImpactResult.Hop(distance,
                        entities.stream().sorted().toList())));
        log.info("[IMPACT] origin={} 结论 hops={}, cycleMarked={}", origin, hops, cycle[0]);
        return new ImpactResult(origin, List.copyOf(hops), cycle[0]);
    }

    /** 从 from 到 to 的一条可达路径（BFS），不可达返回 null；在 lineageLock 下调用。 */
    private List<String> findPath(String from, String to) {
        if (from.equals(to)) {
            return List.of(from);
        }
        Deque<String> queue = new ArrayDeque<>();
        Map<String, String> parent = new LinkedHashMap<>();
        queue.add(from);
        parent.put(from, null);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            for (String next : store.downstreamOf(node).stream().sorted().toList()) {
                if (parent.containsKey(next)) {
                    continue;
                }
                parent.put(next, node);
                if (next.equals(to)) {
                    List<String> path = new ArrayList<>();
                    String cur = to;
                    while (cur != null) {
                        path.add(0, cur);
                        cur = parent.get(cur);
                    }
                    return path;
                }
                queue.add(next);
            }
        }
        return null;
    }

    private LineageResult reject(LineageRequest request, RejectReason reason, String message) {
        String up = Names.canonical(request == null ? null : request.upstream());
        String down = Names.canonical(request == null ? null : request.downstream());
        String source = request == null || isBlank(request.source()) ? "anonymous" : request.source().trim();
        return reject(request, reason, message, up, down, source);
    }

    private LineageResult reject(LineageRequest request, RejectReason reason, String message,
                                 String up, String down, String source) {
        LineageResult result = new LineageResult(up, down, Decision.REJECTED, reason, message);
        store.appendAudit(ActionType.REGISTER_LINEAGE, source,
                up + "->" + down, Decision.REJECTED, reason, message);
        log.info("[LINEAGE] 结论 {}->{} decision=REJECTED reason={} 依据={}", up, down, reason, message);
        return result;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
