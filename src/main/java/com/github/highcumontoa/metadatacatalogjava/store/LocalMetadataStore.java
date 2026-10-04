package com.github.highcumontoa.metadatacatalogjava.store;

import com.github.highcumontoa.metadatacatalogjava.model.ActionType;
import com.github.highcumontoa.metadatacatalogjava.model.AuditRecord;
import com.github.highcumontoa.metadatacatalogjava.model.Decision;
import com.github.highcumontoa.metadatacatalogjava.model.EntityDef;
import com.github.highcumontoa.metadatacatalogjava.model.LineageEdge;
import com.github.highcumontoa.metadatacatalogjava.model.RejectReason;
import com.github.highcumontoa.metadatacatalogjava.util.Names;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 纯内存本地存储：模拟多来源声明的统一目录。
 * <ul>
 *   <li>每个实体一把锁，保证同一实体的并发登记 / 变更串行化、结论确定；</li>
 *   <li>血缘边以 upstream+"->"+downstream 为键天然去重，重复登记不会重复计数；</li>
 *   <li>删除实体时级联清理所有关联血缘边，杜绝悬空血缘；</li>
 *   <li>审计日志只追加、序号单调，接受与拒绝均留痕。</li>
 * </ul>
 * 不依赖任何外部服务。
 */
@Component
public class LocalMetadataStore {

    private final ConcurrentMap<String, EntityDef> entities = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Lock> entityLocks = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, LineageEdge> lineageEdges = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<String>> downstreamAdj = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<String>> upstreamAdj = new ConcurrentHashMap<>();
    private final Lock lineageLock = new ReentrantLock();

    private final NavigableMap<Long, AuditRecord> auditLog = new TreeMap<>();
    private final AtomicLong auditSequence = new AtomicLong(0);
    private final Lock auditLock = new ReentrantLock();

    private static String edgeKey(String upstream, String downstream) {
        return upstream + "->" + downstream;
    }

    /** 获取实体级锁，串行化针对同一实体的写操作。按规范键取锁，书写差异不会各取一把锁。 */
    public Lock lockFor(String qualifiedName) {
        return entityLocks.computeIfAbsent(Names.canonical(qualifiedName), k -> new ReentrantLock());
    }

    public EntityDef getEntity(String qualifiedName) {
        return entities.get(Names.canonical(qualifiedName));
    }

    public Map<String, EntityDef> allEntities() {
        return Map.copyOf(entities);
    }

    public void putEntity(EntityDef entity) {
        entities.put(Names.canonical(entity.getQualifiedName()), entity);
    }

    // ---- 血缘 ----

    public boolean lineageExists(String upstream, String downstream) {
        return lineageEdges.containsKey(edgeKey(upstream, downstream));
    }

    /** 幂等添加一条血缘边；重复调用不产生重复计数。需在 {@link #lineageLock()} 下调用。 */
    public void addLineage(LineageEdge edge) {
        String key = edgeKey(edge.upstream(), edge.downstream());
        if (lineageEdges.putIfAbsent(key, edge) != null) {
            return;
        }
        downstreamAdj.computeIfAbsent(edge.upstream(), k -> ConcurrentHashMap.newKeySet())
                .add(edge.downstream());
        upstreamAdj.computeIfAbsent(edge.downstream(), k -> ConcurrentHashMap.newKeySet())
                .add(edge.upstream());
    }

    public List<LineageEdge> allLineageEdges() {
        return List.copyOf(lineageEdges.values());
    }

    public Set<String> downstreamOf(String qualifiedName) {
        return Set.copyOf(downstreamAdj.getOrDefault(qualifiedName, Set.of()));
    }

    public Set<String> upstreamOf(String qualifiedName) {
        return Set.copyOf(upstreamAdj.getOrDefault(qualifiedName, Set.of()));
    }

    public Lock lineageLock() {
        return lineageLock;
    }

    /**
     * 删除实体并级联清理其所有上下游血缘边，禁止悬空血缘。
     * 需同时持有该实体锁与 lineageLock（由服务层保证加锁顺序）。
     *
     * @return 被清理的血缘边数量
     */
    public int removeEntityAndLineage(String qualifiedName) {
        int removed = 0;
        List<String> keys = new ArrayList<>();
        for (LineageEdge edge : lineageEdges.values()) {
            if (edge.upstream().equals(qualifiedName) || edge.downstream().equals(qualifiedName)) {
                keys.add(edgeKey(edge.upstream(), edge.downstream()));
            }
        }
        for (String key : keys) {
            LineageEdge edge = lineageEdges.remove(key);
            if (edge == null) {
                continue;
            }
            removed++;
            Set<String> downs = downstreamAdj.get(edge.upstream());
            if (downs != null) {
                downs.remove(edge.downstream());
            }
            Set<String> ups = upstreamAdj.get(edge.downstream());
            if (ups != null) {
                ups.remove(edge.upstream());
            }
        }
        downstreamAdj.remove(qualifiedName);
        upstreamAdj.remove(qualifiedName);
        entities.remove(qualifiedName);
        return removed;
    }

    // ---- 审计 ----

    /** 追加一条只增不改的审计记录（接受与拒绝均记录）。 */
    public AuditRecord appendAudit(ActionType action, String operator, String target,
                                   Decision decision, RejectReason reason, String detail) {
        AuditRecord record;
        auditLock.lock();
        try {
            long seq = auditSequence.incrementAndGet();
            record = new AuditRecord(seq, Instant.now(), action, operator, target, decision, reason, detail);
            auditLog.put(seq, record);
        } finally {
            auditLock.unlock();
        }
        return record;
    }

    public List<AuditRecord> auditTrail(String qualifiedName) {
        String qn = Names.canonical(qualifiedName);
        auditLock.lock();
        try {
            return auditLog.values().stream()
                    .filter(r -> r.targetEntity() != null && r.targetEntity().equals(qn))
                    .toList();
        } finally {
            auditLock.unlock();
        }
    }

    public List<AuditRecord> allAudit() {
        auditLock.lock();
        try {
            return List.copyOf(auditLog.values());
        } finally {
            auditLock.unlock();
        }
    }
}
