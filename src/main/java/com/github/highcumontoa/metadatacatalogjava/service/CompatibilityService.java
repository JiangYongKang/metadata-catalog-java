package com.github.highcumontoa.metadatacatalogjava.service;

import com.github.highcumontoa.metadatacatalogjava.dto.ChangeFieldOp;
import com.github.highcumontoa.metadatacatalogjava.dto.FieldResult;
import com.github.highcumontoa.metadatacatalogjava.dto.SchemaChangeRequest;
import com.github.highcumontoa.metadatacatalogjava.dto.SchemaChangeResult;
import com.github.highcumontoa.metadatacatalogjava.model.ActionType;
import com.github.highcumontoa.metadatacatalogjava.model.DataType;
import com.github.highcumontoa.metadatacatalogjava.model.Decision;
import com.github.highcumontoa.metadatacatalogjava.model.EntityDef;
import com.github.highcumontoa.metadatacatalogjava.model.FieldDef;
import com.github.highcumontoa.metadatacatalogjava.model.RejectReason;
import com.github.highcumontoa.metadatacatalogjava.store.LocalMetadataStore;
import com.github.highcumontoa.metadatacatalogjava.util.Names;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 字段变更兼容判定服务。
 *
 * <p>统一兼容策略（破坏性变更一律拒绝并逐操作说明原因，整批不生效）：</p>
 * <ul>
 *   <li>ADD：字段不存在 -> 兼容；字段已存在且类型一致 -> NO_CHANGE；
 *       已存在但类型不同 -> TYPE_CHANGE_INCOMPATIBLE（请走 ALTER_TYPE）；</li>
 *   <li>DROP：删除字段使既有消费方失效，始终判定 FIELD_REMOVED_INCOMPATIBLE；</li>
 *   <li>ALTER_TYPE：仅允许数值放宽
 *       INTEGER -&gt; BIGINT/DECIMAL/DOUBLE、BIGINT -&gt; DECIMAL/DOUBLE；
 *       其余（含收窄、跨族类、相同类型）均 TYPE_CHANGE_INCOMPATIBLE；</li>
 * </ul>
 * 所有操作先在快照上完整判定，全部通过才在实体锁内落库，保证原子性与并发确定性。
 * 已废弃实体 / 无权限操作者分别以 ENTITY_DEPRECATED / PERMISSION_DENIED 拒绝。
 */
@Service
public class CompatibilityService {

    private static final Logger log = LoggerFactory.getLogger(CompatibilityService.class);

    /** 允许的“放宽”类型变更集合。 */
    private static final Map<DataType, Set<DataType>> WIDENING = Map.of(
            DataType.INTEGER, Set.of(DataType.BIGINT, DataType.DECIMAL, DataType.DOUBLE),
            DataType.BIGINT, Set.of(DataType.DECIMAL, DataType.DOUBLE)
    );

    private final LocalMetadataStore store;
    private final TypeDictionary typeDictionary;
    private final PermissionService permissionService;

    public CompatibilityService(LocalMetadataStore store, TypeDictionary typeDictionary,
                                PermissionService permissionService) {
        this.store = store;
        this.typeDictionary = typeDictionary;
        this.permissionService = permissionService;
    }

    public SchemaChangeResult apply(SchemaChangeRequest request) {
        log.info("[CHANGE] 输入 operator={}, qualifiedName={}, ops={}",
                request == null ? null : request.operator(),
                request == null ? null : request.qualifiedName(),
                request == null || request.ops() == null ? null
                        : request.ops().stream().map(o -> o.op() + ":" + o.fieldName() + ":" + o.rawType()).toList());

        if (request == null || isBlank(request.qualifiedName()) || isBlank(request.operator())) {
            return reject(request == null ? null : Names.canonical(request.qualifiedName()),
                    null, RejectReason.INVALID_PAYLOAD,
                    "qualifiedName 与 operator 均为必填项", List.of());
        }
        String qn = Names.canonical(request.qualifiedName());
        String operator = request.operator().trim();
        if (request.ops() == null || request.ops().isEmpty()) {
            return reject(qn, operator, RejectReason.INVALID_PAYLOAD,
                    "ops 不能为空", List.of());
        }

        var lock = store.lockFor(qn);
        lock.lock();
        try {
            EntityDef entity = store.getEntity(qn);
            if (entity == null) {
                return reject(qn, operator, RejectReason.UNKNOWN_ENTITY,
                        "实体不存在：" + qn + "（变更前请先登记）", List.of());
            }
            // 写保护优先级统一：已废弃实体即使是 admin 也一律拒绝，原因与权限拒绝明确区分
            if (entity.isDeprecated()) {
                return reject(qn, operator, RejectReason.ENTITY_DEPRECATED,
                        "实体 " + qn + " 已废弃，拒绝一切写操作", List.of());
            }
            if (!permissionService.canWrite(qn, operator)) {
                return reject(qn, operator, RejectReason.PERMISSION_DENIED,
                        "操作者 '" + operator + "' 对实体 " + qn + " 无修改权限", List.of());
            }

            // 批次内校验：未知操作码、缺字段名、非法目标类型
            List<FieldResult> results = new ArrayList<>();
            List<Runnable> pendingMutations = new ArrayList<>();
            boolean anyChange = false;

            // 基于快照模拟本批次内的字段状态，保证同批次操作之间也可判定
            Map<String, FieldDef> working = new LinkedHashMap<>(entity.getFields());

            for (ChangeFieldOp op : request.ops()) {
                String opName = op.op() == null ? "" : op.op().trim().toUpperCase();
                String fieldName = Names.canonical(op.fieldName());
                if (fieldName == null) {
                    results.add(new FieldResult(null, Decision.REJECTED, RejectReason.MISSING_FIELD_NAME,
                            "变更操作缺少字段名：" + opName));
                    continue;
                }
                switch (opName) {
                    case "ADD" -> {
                        Optional<DataType> target = typeDictionary.normalize(op.rawType());
                        if (target.isEmpty()) {
                            results.add(new FieldResult(fieldName, Decision.REJECTED, RejectReason.UNKNOWN_TYPE,
                                    "ADD 的目标类型 '" + op.rawType() + "' 不在类型字典中"));
                            break;
                        }
                        FieldDef exists = working.get(fieldName);
                        if (exists == null) {
                            DataType t = target.get();
                            results.add(new FieldResult(fieldName, Decision.ACCEPTED, RejectReason.NONE,
                                    "新增字段（纯新增）向后兼容，规范类型 " + t));
                            pendingMutations.add(() -> {
                                Map<String, String> src = new LinkedHashMap<>();
                                src.put(operator, op.rawType().trim());
                                entity.putField(new FieldDef(fieldName, t, src, false));
                            });
                            working.put(fieldName, new FieldDef(fieldName, t, Map.of(operator, op.rawType().trim()), false));
                            anyChange = true;
                        } else if (exists.getCanonicalType() == target.get()) {
                            results.add(new FieldResult(fieldName, Decision.NO_CHANGE, RejectReason.NONE,
                                    "字段已存在且类型一致，无需变更"));
                        } else {
                            results.add(new FieldResult(fieldName, Decision.REJECTED,
                                    RejectReason.TYPE_CHANGE_INCOMPATIBLE,
                                    "字段已存在且类型为 " + exists.getCanonicalType()
                                            + "，ADD 不允许隐式改类型，请使用 ALTER_TYPE"));
                        }
                    }
                    case "DROP" -> {
                        if (!working.containsKey(fieldName)) {
                            results.add(new FieldResult(fieldName, Decision.REJECTED, RejectReason.UNKNOWN_FIELD,
                                    "实体 " + qn + " 存在，但字段 " + fieldName
                                            + " 不存在，无可删除对象（UNKNOWN_FIELD，区别于实体不存在）"));
                        } else {
                            results.add(new FieldResult(fieldName, Decision.REJECTED,
                                    RejectReason.FIELD_REMOVED_INCOMPATIBLE,
                                    "删除字段 " + fieldName + " 会使既有消费方失效，按兼容策略判定为破坏性变更"));
                        }
                    }
                    case "ALTER_TYPE" -> {
                        Optional<DataType> target = typeDictionary.normalize(op.rawType());
                        if (target.isEmpty()) {
                            results.add(new FieldResult(fieldName, Decision.REJECTED, RejectReason.UNKNOWN_TYPE,
                                    "ALTER_TYPE 的目标类型 '" + op.rawType() + "' 不在类型字典中"));
                            break;
                        }
                        FieldDef exists = working.get(fieldName);
                        if (exists == null) {
                            results.add(new FieldResult(fieldName, Decision.REJECTED, RejectReason.UNKNOWN_FIELD,
                                    "实体 " + qn + " 存在，但字段 " + fieldName
                                            + " 不存在，无法 ALTER_TYPE（UNKNOWN_FIELD，区别于实体不存在）"));
                            break;
                        }
                        DataType from = exists.getCanonicalType();
                        DataType to = target.get();
                        if (from == to) {
                            results.add(new FieldResult(fieldName, Decision.REJECTED,
                                    RejectReason.TYPE_CHANGE_INCOMPATIBLE,
                                    "类型未发生变化（" + from + "），不属于有效变更"));
                        } else if (isWidening(from, to)) {
                            results.add(new FieldResult(fieldName, Decision.ACCEPTED, RejectReason.NONE,
                                    "类型放宽 " + from + " -> " + to + " 向后兼容"));
                            DataType t = to;
                            pendingMutations.add(() -> {
                                Map<String, String> src = new LinkedHashMap<>(exists.getSources());
                                src.put(operator, op.rawType().trim());
                                entity.putField(new FieldDef(fieldName, t, src, exists.isDeprecated()));
                            });
                            working.put(fieldName, new FieldDef(fieldName, to,
                                    new LinkedHashMap<>(exists.getSources()), exists.isDeprecated()));
                            anyChange = true;
                        } else {
                            results.add(new FieldResult(fieldName, Decision.REJECTED,
                                    RejectReason.TYPE_CHANGE_INCOMPATIBLE,
                                    "类型变更 " + from + " -> " + to
                                            + " 不属于允许的放宽集合（仅允许 INTEGER->BIGINT/DECIMAL/DOUBLE、"
                                            + "BIGINT->DECIMAL/DOUBLE），为破坏性变更"));
                        }
                    }
                    default -> results.add(new FieldResult(fieldName, Decision.REJECTED,
                            RejectReason.INVALID_PAYLOAD, "未知变更操作码 '" + op.op()
                                    + "'，仅支持 ADD / DROP / ALTER_TYPE"));
                }
            }

            boolean rejected = results.stream().anyMatch(r -> r.decision() == Decision.REJECTED);
            if (rejected) {
                String message = "存在破坏性或非法变更，整批拒绝，实体状态保持不变";
                return reject(qn, operator, primaryReason(results), message, results);
            }

            // 全部通过：原子落库
            pendingMutations.forEach(Runnable::run);
            Decision decision = anyChange ? Decision.MERGED : Decision.NO_CHANGE;
            String message = anyChange ? "兼容变更已生效" : "所有操作均为幂等无变化";
            SchemaChangeResult result = new SchemaChangeResult(qn, decision, RejectReason.NONE,
                    message, List.copyOf(results));
            store.appendAudit(ActionType.CHANGE_SCHEMA, operator, qn, decision, RejectReason.NONE,
                    message + " | 操作结论=" + results);
            log.info("[CHANGE] qualifiedName={} decision={} 依据={}", qn, decision, results);
            return result;
        } finally {
            lock.unlock();
        }
    }

    private static boolean isWidening(DataType from, DataType to) {
        return WIDENING.getOrDefault(from, Set.of()).contains(to);
    }

    private SchemaChangeResult reject(String qn, String operator, RejectReason reason,
                                      String message, List<FieldResult> results) {
        SchemaChangeResult result = new SchemaChangeResult(qn, Decision.REJECTED, reason, message,
                List.copyOf(results));
        store.appendAudit(ActionType.CHANGE_SCHEMA, operator, qn, Decision.REJECTED, reason,
                message + (results.isEmpty() ? "" : " | 操作结论=" + results));
        log.info("[CHANGE] qualifiedName={} decision=REJECTED reason={} 依据={} ops={}",
                qn, reason, message, results);
        return result;
    }

    private static RejectReason primaryReason(List<FieldResult> results) {
        return results.stream()
                .filter(r -> r.decision() == Decision.REJECTED)
                .map(FieldResult::reason)
                .findFirst()
                .orElse(RejectReason.INVALID_PAYLOAD);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
