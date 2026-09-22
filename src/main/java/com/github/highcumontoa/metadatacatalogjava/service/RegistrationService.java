package com.github.highcumontoa.metadatacatalogjava.service;

import com.github.highcumontoa.metadatacatalogjava.dto.FieldClaim;
import com.github.highcumontoa.metadatacatalogjava.dto.FieldResult;
import com.github.highcumontoa.metadatacatalogjava.dto.RegistrationRequest;
import com.github.highcumontoa.metadatacatalogjava.dto.RegistrationResult;
import com.github.highcumontoa.metadatacatalogjava.model.ActionType;
import com.github.highcumontoa.metadatacatalogjava.model.DataType;
import com.github.highcumontoa.metadatacatalogjava.model.Decision;
import com.github.highcumontoa.metadatacatalogjava.model.EntityDef;
import com.github.highcumontoa.metadatacatalogjava.model.EntityKind;
import com.github.highcumontoa.metadatacatalogjava.model.FieldDef;
import com.github.highcumontoa.metadatacatalogjava.model.RejectReason;
import com.github.highcumontoa.metadatacatalogjava.store.LocalMetadataStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

/**
 * 实体 / 字段登记与归一合并服务。
 *
 * <p>确定性规则：</p>
 * <ol>
 *   <li>输入级问题（缺来源 / 缺实体名 / 非法实体种类）整体拒绝，原因 INVALID_PAYLOAD；</li>
 *   <li>字段级问题逐条给出可区分原因：MISSING_FIELD_NAME、UNKNOWN_TYPE、
 *       DUPLICATE_FIELD_IN_PAYLOAD、TYPE_CONFLICT；任何一条被拒绝则整体 REJECTED 且不落库；</li>
 *   <li>新实体 -&gt; ACCEPTED；既有实体补充新字段 / 新来源 -&gt; MERGED；声明完全一致 -&gt; NO_CHANGE；</li>
 *   <li>同实体的并发登记通过实体锁串行化，结论与单线程重复执行一致；</li>
 *   <li>无权限 / 已废弃实体分别以 PERMISSION_DENIED、ENTITY_DEPRECATED 拒绝，不留歧义。</li>
 * </ol>
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final LocalMetadataStore store;
    private final TypeDictionary typeDictionary;
    private final PermissionService permissionService;

    public RegistrationService(LocalMetadataStore store, TypeDictionary typeDictionary,
                               PermissionService permissionService) {
        this.store = store;
        this.typeDictionary = typeDictionary;
        this.permissionService = permissionService;
    }

    public RegistrationResult register(RegistrationRequest request) {
        log.info("[REGISTER] 输入 source={}, qualifiedName={}, kind={}, fields={}",
                request == null ? null : request.source(),
                request == null ? null : request.qualifiedName(),
                request == null ? null : request.kind(),
                request == null || request.fields() == null ? null
                        : request.fields().stream().map(f -> f.name() + ":" + f.rawType()).toList());

        if (request == null || isBlank(request.source()) || isBlank(request.qualifiedName())) {
            return rejectGlobal(request, RejectReason.INVALID_PAYLOAD,
                    "source 与 qualifiedName 均为必填项", List.of());
        }
        String qn = request.qualifiedName().trim();
        String source = request.source().trim();

        EntityKind kind;
        try {
            kind = request.kind() == null ? null : EntityKind.valueOf(request.kind().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            kind = null;
        }
        if (kind == null) {
            return rejectGlobal(request, RejectReason.INVALID_PAYLOAD,
                    "kind 非法，必须为 TABLE / VIEW / STREAM 之一", List.of());
        }
        if (request.fields() == null) {
            return rejectGlobal(request, RejectReason.INVALID_PAYLOAD,
                    "fields 不能为 null（无字段请传空列表）", List.of());
        }

        var lock = store.lockFor(qn);
        lock.lock();
        try {
            EntityDef existing = store.getEntity(qn);

            if (existing != null && existing.isDeprecated()) {
                return rejectGlobal(request, RejectReason.ENTITY_DEPRECATED,
                        "实体 " + qn + " 已废弃，拒绝登记", List.of());
            }
            if (!permissionService.canWrite(qn, source)) {
                return rejectGlobal(request, RejectReason.PERMISSION_DENIED,
                        "来源/操作者 '" + source + "' 对实体 " + qn + " 无登记权限", List.of());
            }

            List<FieldClaim> claims = request.fields();
            // 逐字段结论与输入 claims 严格同下标，便于按原因区分定位
            FieldResult[] fieldResults = new FieldResult[claims.size()];
            DataType[] claimedTypes = new DataType[claims.size()];
            Set<String> seenNames = new HashSet<>();
            boolean anyRejected = false;

            // ---- 逐字段校验：缺失、非法类型、批次内重名 ----
            for (int i = 0; i < claims.size(); i++) {
                FieldClaim claim = claims.get(i);
                String name = claim.name() == null ? null : claim.name().trim();
                if (name == null || name.isEmpty()) {
                    fieldResults[i] = new FieldResult(null, Decision.REJECTED, RejectReason.MISSING_FIELD_NAME,
                            "存在未命名字段，原始声明=" + claim.rawType());
                    anyRejected = true;
                    continue;
                }
                if (!seenNames.add(name)) {
                    fieldResults[i] = new FieldResult(name, Decision.REJECTED,
                            RejectReason.DUPLICATE_FIELD_IN_PAYLOAD,
                            "同一批次中字段 " + name + " 被声明多次");
                    anyRejected = true;
                    continue;
                }
                Optional<DataType> normalized = typeDictionary.normalize(claim.rawType());
                if (normalized.isEmpty()) {
                    fieldResults[i] = new FieldResult(name, Decision.REJECTED, RejectReason.UNKNOWN_TYPE,
                            "原始类型 '" + claim.rawType() + "' 不在类型字典中，无法归一");
                    anyRejected = true;
                    continue;
                }
                claimedTypes[i] = normalized.get();
            }
            if (anyRejected) {
                return finishRejected(qn, Arrays.asList(fieldResults), source,
                        "存在非法字段声明，整批登记被拒绝且不写入任何数据");
            }

            // ---- 与既有定义比对：类型冲突则拒绝，不静默覆盖 ----
            if (existing != null) {
                for (int i = 0; i < claims.size(); i++) {
                    String name = claims.get(i).name().trim();
                    FieldDef prior = existing.getFields().get(name);
                    if (prior != null && prior.getCanonicalType() != claimedTypes[i]) {
                        fieldResults[i] = new FieldResult(name, Decision.REJECTED, RejectReason.TYPE_CONFLICT,
                                "字段 " + name + " 已存在且规范类型为 " + prior.getCanonicalType()
                                        + "，本次声明为 " + claimedTypes[i] + "，拒绝覆盖");
                    }
                }
                if (Arrays.stream(fieldResults).filter(r -> r != null).anyMatch(r -> r.decision() == Decision.REJECTED)) {
                    return finishRejected(qn, Arrays.asList(fieldResults), source,
                            "存在与既有定义冲突的字段，整批登记被拒绝且不写入任何数据");
                }
            }

            // ---- 合并 / 新建 ----
            boolean isNew = existing == null;
            EntityDef entity = isNew ? new EntityDef(qn, kind, false) : existing;
            boolean addedField = false;
            boolean addedSourceOnField = false;

            for (int i = 0; i < claims.size(); i++) {
                FieldClaim claim = claims.get(i);
                String name = claim.name().trim();
                DataType canonical = claimedTypes[i];
                FieldDef prior = entity.getFields().get(name);
                if (prior == null) {
                    Map<String, String> srcMap = new LinkedHashMap<>();
                    srcMap.put(source, claim.rawType().trim());
                    entity.putField(new FieldDef(name, canonical, srcMap, false));
                    fieldResults[i] = new FieldResult(name, Decision.ACCEPTED, RejectReason.NONE,
                            "新登记字段，规范类型 " + canonical);
                    addedField = true;
                } else {
                    String priorRaw = prior.getSources().get(source);
                    if (priorRaw == null) {
                        entity.putField(prior.withSource(source, claim.rawType().trim()));
                        addedSourceOnField = true;
                        fieldResults[i] = new FieldResult(name, Decision.MERGED, RejectReason.NONE,
                                "字段已存在，补充来源 " + source + "（原始类型 " + claim.rawType().trim()
                                        + " 与规范类型 " + canonical + " 一致）");
                    } else {
                        fieldResults[i] = new FieldResult(name, Decision.NO_CHANGE, RejectReason.NONE,
                                "来源 " + source + " 此前已以相同规范类型声明该字段");
                    }
                }
            }

            entity.addSource(source);
            if (isNew) {
                store.putEntity(entity);
            }

            Decision decision;
            String message;
            if (isNew) {
                decision = Decision.ACCEPTED;
                message = "新实体登记成功";
            } else if (addedField || addedSourceOnField) {
                decision = Decision.MERGED;
                message = "与既有实体合并：" + (addedField ? "新增字段 " : "")
                        + (addedSourceOnField ? "补充字段来源（source=" + source + "）" : "");
            } else {
                decision = Decision.NO_CHANGE;
                message = "声明与既有定义完全一致，幂等返回，未产生任何变化";
            }

            RegistrationResult result = new RegistrationResult(qn, decision, RejectReason.NONE,
                    message, Arrays.asList(fieldResults));
            store.appendAudit(ActionType.REGISTER_ENTITY, source, qn, decision, RejectReason.NONE, message);
            log.info("[REGISTER] 结论 qualifiedName={}, decision={}, 依据={}", qn, decision, message);
            return result;
        } finally {
            lock.unlock();
        }
    }

    private RegistrationResult finishRejected(String qn, List<FieldResult> fieldResults, String source,
                                              String message) {
        List<FieldResult> nonNull = fieldResults.stream().filter(r -> r != null).toList();
        RegistrationResult result = new RegistrationResult(qn, Decision.REJECTED,
                primaryReason(nonNull), message, nonNull);
        fieldResults = nonNull;
        store.appendAudit(ActionType.REGISTER_ENTITY, source, qn, Decision.REJECTED,
                primaryReason(fieldResults), message + " | 字段级结论=" + fieldResults);
        log.info("[REGISTER] 结论 qualifiedName={}, decision=REJECTED, 依据={} | 字段级结论={}",
                qn, message, fieldResults);
        return result;
    }

    private RegistrationResult rejectGlobal(RegistrationRequest request, RejectReason reason,
                                            String message, List<FieldResult> fieldResults) {
        String qn = request == null || request.qualifiedName() == null ? null : request.qualifiedName();
        String source = request == null ? null : request.source();
        RegistrationResult result = new RegistrationResult(qn, Decision.REJECTED, reason, message,
                List.copyOf(fieldResults));
        store.appendAudit(ActionType.REGISTER_ENTITY, source, qn, Decision.REJECTED, reason, message);
        log.info("[REGISTER] 结论 qualifiedName={}, decision=REJECTED, reason={}, 依据={}",
                qn, reason, message);
        return result;
    }

    private static RejectReason primaryReason(List<FieldResult> fieldResults) {
        return fieldResults.stream()
                .filter(r -> r != null && r.decision() == Decision.REJECTED)
                .map(FieldResult::reason)
                .findFirst()
                .orElse(RejectReason.INVALID_PAYLOAD);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
