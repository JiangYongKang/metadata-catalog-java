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
 * <p>归一规则（所有写入口一致，见 {@link NameNormalizer}）：实体名与字段名
 * 仅大小写或首尾空白差异时视为同一对象，合并到同一份定义，不各自建档。</p>
 *
 * <p>确定性规则：</p>
 * <ol>
 *   <li>输入级问题（缺来源 / 缺实体名 / 非法实体种类）整体拒绝，原因 INVALID_PAYLOAD；</li>
 *   <li>同名实体被声明为不同种类 -&gt; 整体拒绝 KIND_CONFLICT，绝不悄悄合成一条记录，
 *       与字段级 TYPE_CONFLICT、输入级 INVALID_PAYLOAD 明确区分；</li>
 *   <li>字段级问题逐条给出可区分原因：MISSING_FIELD_NAME、UNKNOWN_TYPE、
 *       DUPLICATE_FIELD_IN_PAYLOAD、TYPE_CONFLICT；任何一条被拒绝则整体 REJECTED，
 *       规范数据不落库（落败来源仅记入字段拒绝台账，用于交代每个来源的去向）；</li>
 *   <li>同名字段出现多种规范类型时按 {@link TypeArbitrator} 的固定类型优先级裁决：
 *       优先级更高的声明取代既有规范（既有生效来源转入拒绝台账），
 *       优先级更低的声明以 TYPE_CONFLICT 拒绝——与到达顺序、并发调度无关，
 *       同一组声明的最终规范定义与每个来源的去向完全一致；</li>
 *   <li>新实体 -&gt; ACCEPTED；既有实体补充新字段 / 新来源 / 裁决取代 -&gt; MERGED；
 *       声明完全一致 -&gt; NO_CHANGE；</li>
 *   <li>同实体的并发登记通过实体锁串行化，配合确定性裁决保证收敛一致；</li>
 *   <li>无权限 / 已废弃实体分别以 PERMISSION_DENIED、ENTITY_DEPRECATED 拒绝，不留歧义。</li>
 * </ol>
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final LocalMetadataStore store;
    private final TypeDictionary typeDictionary;
    private final PermissionService permissionService;
    private final TypeArbitrator typeArbitrator;

    public RegistrationService(LocalMetadataStore store, TypeDictionary typeDictionary,
                               PermissionService permissionService, TypeArbitrator typeArbitrator) {
        this.store = store;
        this.typeDictionary = typeDictionary;
        this.permissionService = permissionService;
        this.typeArbitrator = typeArbitrator;
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
        // 归一：仅大小写 / 首尾空白差异的实体名视为同一对象
        String qn = NameNormalizer.normalize(request.qualifiedName());
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
            if (existing != null && existing.getKind() != kind) {
                return rejectGlobal(request, RejectReason.KIND_CONFLICT,
                        "实体 " + qn + " 已登记为 " + existing.getKind()
                                + "，本次声明为 " + kind + "，种类不符，拒绝合并", List.of());
            }

            List<FieldClaim> claims = request.fields();
            // 逐字段结论与输入 claims 严格同下标，便于按原因区分定位
            FieldResult[] fieldResults = new FieldResult[claims.size()];
            DataType[] claimedTypes = new DataType[claims.size()];
            String[] normalizedNames = new String[claims.size()];
            Set<String> seenNames = new HashSet<>();
            boolean anyRejected = false;

            // ---- 逐字段校验：缺失、非法类型、批次内重名（均按归一后的字段名判定）----
            for (int i = 0; i < claims.size(); i++) {
                FieldClaim claim = claims.get(i);
                String name = NameNormalizer.normalize(claim.name());
                if (name == null || name.isEmpty()) {
                    fieldResults[i] = new FieldResult(null, Decision.REJECTED, RejectReason.MISSING_FIELD_NAME,
                            "存在未命名字段，原始声明=" + claim.rawType());
                    anyRejected = true;
                    continue;
                }
                normalizedNames[i] = name;
                if (!seenNames.add(name)) {
                    fieldResults[i] = new FieldResult(name, Decision.REJECTED,
                            RejectReason.DUPLICATE_FIELD_IN_PAYLOAD,
                            "同一批次中字段 " + name + " 被声明多次（归一后重名）");
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
                fillBatchAborted(fieldResults, normalizedNames);
                return finishRejected(qn, Arrays.asList(fieldResults), source,
                        "存在非法字段声明，整批登记被拒绝且不写入任何数据");
            }

            // ---- 与既有定义做确定性裁决：按固定类型优先级定胜负，与到达顺序无关 ----
            // 落败来源立即记入拒绝台账（仅记录去向，不改规范数据）；
            // 胜出取代属于规范数据变更，暂存到本批次全部通过后再落库。
            Map<String, FieldDef> pendingSupersede = new LinkedHashMap<>();
            if (existing != null) {
                for (int i = 0; i < claims.size(); i++) {
                    String name = normalizedNames[i];
                    FieldDef prior = existing.getFields().get(name);
                    if (prior == null || prior.getCanonicalType() == claimedTypes[i]) {
                        continue;
                    }
                    DataType claimed = claimedTypes[i];
                    DataType incumbent = prior.getCanonicalType();
                    String basis = typeArbitrator.explain(claimed, incumbent);
                    if (typeArbitrator.challengerWins(claimed, incumbent)) {
                        fieldResults[i] = new FieldResult(name, Decision.MERGED, RejectReason.NONE,
                                "声明类型 " + claimed + " 取代既有规范类型 " + incumbent + "（" + basis
                                        + "），既有生效来源 " + prior.getSources().keySet() + " 转入拒绝台账");
                        pendingSupersede.put(name,
                                prior.supersededBy(claimed, source, claims.get(i).rawType().trim()));
                    } else {
                        fieldResults[i] = new FieldResult(name, Decision.REJECTED, RejectReason.TYPE_CONFLICT,
                                "字段 " + name + " 的声明类型 " + claimed + " 不敌既有规范类型 " + incumbent
                                        + "（" + basis + "），拒绝覆盖");
                        // 拒绝台账：仅记录去向，不属于规范数据落库
                        existing.putField(prior.withRejectedClaim(source, claims.get(i).rawType().trim()));
                    }
                }
                if (Arrays.stream(fieldResults).filter(r -> r != null)
                        .anyMatch(r -> r.decision() == Decision.REJECTED)) {
                    fillBatchAborted(fieldResults, normalizedNames);
                    return finishRejected(qn, Arrays.asList(fieldResults), source,
                            "存在与既有定义冲突的字段，整批登记被拒绝，规范数据不写入任何变化");
                }
            }

            // ---- 合并 / 新建（本批次全部通过，原子落库）----
            boolean isNew = existing == null;
            EntityDef entity = isNew ? new EntityDef(qn, kind, false) : existing;
            boolean addedField = false;
            boolean addedSourceOnField = false;
            boolean superseded = false;

            for (int i = 0; i < claims.size(); i++) {
                FieldClaim claim = claims.get(i);
                String name = normalizedNames[i];
                DataType canonical = claimedTypes[i];
                FieldDef supersede = pendingSupersede.get(name);
                if (supersede != null) {
                    entity.putField(supersede);
                    superseded = true;
                    continue;
                }
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
            } else if (addedField || addedSourceOnField || superseded) {
                decision = Decision.MERGED;
                message = "与既有实体合并：" + (addedField ? "新增字段 " : "")
                        + (addedSourceOnField ? "补充字段来源（source=" + source + "）" : "")
                        + (superseded ? "按固定优先级裁决取代既有规范类型" : "");
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

    /** 整批拒绝时，为本身合法但未落库的声明补一条逐条结论，保证批次内每条声明都有去向说明。 */
    private static void fillBatchAborted(FieldResult[] fieldResults, String[] names) {
        for (int i = 0; i < fieldResults.length; i++) {
            if (fieldResults[i] == null) {
                fieldResults[i] = new FieldResult(names[i], Decision.NO_CHANGE, RejectReason.NONE,
                        "声明本身合法，但同批次存在非法/冲突声明，整批拒绝，本声明未落库");
            }
        }
    }

    private RegistrationResult finishRejected(String qn, List<FieldResult> fieldResults, String source,
                                              String message) {
        List<FieldResult> nonNull = fieldResults.stream().filter(r -> r != null).toList();
        RegistrationResult result = new RegistrationResult(qn, Decision.REJECTED,
                primaryReason(nonNull), message, nonNull);
        store.appendAudit(ActionType.REGISTER_ENTITY, source, qn, Decision.REJECTED,
                primaryReason(nonNull), message + " | 字段级结论=" + nonNull);
        log.info("[REGISTER] 结论 qualifiedName={}, decision=REJECTED, 依据={} | 字段级结论={}",
                qn, message, nonNull);
        return result;
    }

    private RegistrationResult rejectGlobal(RegistrationRequest request, RejectReason reason,
                                            String message, List<FieldResult> fieldResults) {
        String qn = request == null ? null : NameNormalizer.normalize(request.qualifiedName());
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
