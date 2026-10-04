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
import com.github.highcumontoa.metadatacatalogjava.util.Names;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.lang.Nullable;

/**
 * 实体 / 字段登记与归一收敛服务。
 *
 * <p>对象识别（所有写入口共用 {@link Names} 规则）：实体限定名与字段名先去首尾空白、
 * 再按 ROOT Locale 大小写折叠；书写差异不会各建一份记录。</p>
 *
 * <p>裁决规则（与请求到达顺序、线程调度无关，只取决于声明内容）：</p>
 * <ol>
 *   <li>种类裁决：同一规范实体名若被声明为不同种类（TABLE/VIEW/STREAM），以 KIND_CONFLICT
 *       整批拒绝，绝不静默合成一条记录；</li>
 *   <li>批次内裁决：同一批次内同规范字段名重复且类型一致 -> DUPLICATE_FIELD_IN_PAYLOAD；
 *       重复但类型不同 -> FIELD_CONFLICT_IN_PAYLOAD；</li>
 *   <li>字段规范类型裁决：字段的全部已登记声明（胜出+落败）加上本批声明构成裁决全集，
 *       规范类型取“各来源声明的规范类型中枚举名字典序最小者”
 *       （即 BIGINT &lt; BOOLEAN &lt; DECIMAL &lt; DOUBLE &lt; INTEGER &lt; STRING &lt; TIMESTAMP），
 *       作为公开、固定、可复算的裁决依据；不是谁先到谁说了算；</li>
 *   <li>来源去向：声明胜出版本的来源挂在字段上；声明落败版本的来源以 TYPE_CONFLICT
 *       拒绝并记录其最后声明，绝不污染规范定义；胜出者易主时旧胜出者整体转入落败集合，
 *       结论同样只取决于声明全集；</li>
 *   <li>一个批次中只要存在非法、互相冲突或种类不符的声明，整批拒绝、逐条给结论，不落任何数据。</li>
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
        String qn = Names.canonical(request.qualifiedName());
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
                String basis = "实体 " + qn + " 已按种类 " + existing.getKind()
                        + " 登记，本次来源 " + source + " 声明种类 " + kind
                        + "，种类不一致不得合并（KIND_CONFLICT），整批拒绝且不写入任何数据";
                List<FieldResult> perClaim = request.fields().stream()
                        .map(f -> new FieldResult(Names.canonical(f.name()), Decision.REJECTED,
                                RejectReason.KIND_CONFLICT, "所属实体种类冲突，本条声明随整批拒绝"))
                        .toList();
                return finishRejected(qn, perClaim, source, basis, RejectReason.KIND_CONFLICT);
            }

            List<FieldClaim> claims = request.fields();
            FieldResult[] fieldResults = new FieldResult[claims.size()];
            DataType[] claimedTypes = new DataType[claims.size()];
            String[] canonicalNames = new String[claims.size()];
            // 规范字段名 -> 批次内首次出现下标及其规范类型，用于批次内重名/互相冲突判定
            Map<String, Integer> firstIndex = new LinkedHashMap<>();
            boolean anyRejected = false;

            // ---- 逐字段输入校验：缺失、非法类型、批次内重名/互相冲突 ----
            for (int i = 0; i < claims.size(); i++) {
                FieldClaim claim = claims.get(i);
                String name = Names.canonical(claim.name());
                canonicalNames[i] = name;
                if (name == null) {
                    fieldResults[i] = new FieldResult(null, Decision.REJECTED, RejectReason.MISSING_FIELD_NAME,
                            "存在未命名字段，原始声明=" + claim.rawType());
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
                Integer first = firstIndex.get(name);
                if (first != null) {
                    if (claimedTypes[first].equals(claimedTypes[i])) {
                        fieldResults[i] = new FieldResult(name, Decision.REJECTED,
                                RejectReason.DUPLICATE_FIELD_IN_PAYLOAD,
                                "同一批次中字段 " + name + " 被声明多次（规范类型同为 " + claimedTypes[i] + "）");
                    } else {
                        fieldResults[i] = new FieldResult(name, Decision.REJECTED,
                                RejectReason.FIELD_CONFLICT_IN_PAYLOAD,
                                "同一批次中字段 " + name + " 被声明为不同类型："
                                        + claimedTypes[first] + " 与 " + claimedTypes[i] + "，声明互相冲突");
                    }
                    anyRejected = true;
                } else {
                    firstIndex.put(name, i);
                }
            }
            if (anyRejected) {
                return finishRejected(qn, validClaimsMarkedRejected(fieldResults, canonicalNames,
                        primaryReason(Arrays.asList(fieldResults))), source,
                        "存在非法或批次内互相冲突的字段声明，整批登记被拒绝且不写入任何数据",
                        primaryReason(Arrays.asList(fieldResults)));
            }

            // ---- 在声明全集上做内容裁决（不看到达顺序），任何一条落败则整批不做结构性落库 ----
            EntityDef entity = existing == null ? new EntityDef(qn, kind, false) : existing;
            boolean isNew = existing == null;
            boolean addedField = false;
            boolean addedSourceOnField = false;
            // 结构性写动作（新建字段 / 来源并入 / 翻转）先挂起：整批全部通过才落库；
            // 落败台账单独存放：合法但落败的声明本身要记录其去向（可追溯 + 完整裁决全集），
            // 它不是对规范定义的修改，即使整批因其它原因被拒也只允许台账这一项落地。
            Map<String, FieldDef> pendingFields = new LinkedHashMap<>();
            Map<String, FieldDef> pendingLoserLedgers = new LinkedHashMap<>();
            // 翻转时被迁出胜出集合的来源（仅在整批接受后随落库补审计，使每个来源去向变化可追溯）
            List<String> displacedSources = new ArrayList<>();

            for (int i = 0; i < claims.size(); i++) {
                String name = canonicalNames[i];
                DataType claimType = claimedTypes[i];
                String rawType = claims.get(i).rawType().trim();
                FieldDef prior = pendingFields.getOrDefault(name, entity.getFields().get(name));

                if (prior == null) {
                    // 新字段：唯一声明直接建立规范
                    Map<String, String> srcMap = new LinkedHashMap<>();
                    srcMap.put(source, rawType);
                    FieldDef created = new FieldDef(name, claimType, srcMap, false);
                    pendingFields.put(name, created);
                    fieldResults[i] = new FieldResult(name, Decision.ACCEPTED, RejectReason.NONE,
                            "新登记字段，按唯一来源声明建立规范类型 " + claimType);
                    addedField = true;
                    continue;
                }

                // 既有字段：裁决全集 = 既有胜出+落败声明（去掉本来源旧声明）+ 本次声明
                Map<String, DataType> universe = new HashMap<>();
                Map<String, String> universeRaw = new HashMap<>();
                prior.getSources().forEach((s, raw) -> {
                    universe.put(s, prior.getCanonicalType());
                    universeRaw.put(s, raw);
                });
                prior.getLosingClaims().forEach((s, raw) ->
                        typeDictionary.normalize(raw).ifPresent(t -> {
                            universe.put(s, t);
                            universeRaw.put(s, raw);
                        }));
                universe.put(source, claimType);
                universeRaw.put(source, rawType);

                DataType winner = universe.values().stream().min(Enum::compareTo).orElseThrow();
                boolean sourceWasWinner = prior.getSources().containsKey(source);
                String priorRaw = prior.getSources().get(source);

                if (claimType != winner) {
                    fieldResults[i] = new FieldResult(name, Decision.REJECTED, RejectReason.TYPE_CONFLICT,
                            "字段 " + name + " 的声明全集经内容裁决规范类型为 " + winner
                                    + "（裁决依据：规范类型枚举名字典序最小），来源 " + source
                                    + " 本次声明 " + claimType + " 落败，不挂接到规范定义，规范类型保持 "
                                    + prior.getCanonicalType() + "；其落败去向已登记到字段落败台账");
                    anyRejected = true;
                    // 合法但落败的声明：仅持久化其落败台账（不修改规范类型与胜出集合），
                    // 使每个来源的最终去向可追溯、且后续裁决始终在完整声明全集上重算；
                    // 本批任何新建字段 / 来源并入 / 翻转等结构性变更全部停留在工作副本并随整批丢弃。
                    Map<String, String> loserSnapshot = new LinkedHashMap<>(prior.getLosingClaims());
                    loserSnapshot.put(source, rawType);
                    FieldDef ledger = new FieldDef(name, prior.getCanonicalType(),
                            prior.getSources(), loserSnapshot, prior.isDeprecated());
                    pendingLoserLedgers.put(name, ledger);
                    continue;
                }

                if (sourceWasWinner && priorRaw != null
                        && prior.getCanonicalType() == winner
                        && priorRaw.trim().equalsIgnoreCase(rawType)) {
                    fieldResults[i] = new FieldResult(name, Decision.NO_CHANGE, RejectReason.NONE,
                            "来源 " + source + " 此前已以相同规范类型声明该字段，幂等无变化");
                    continue;
                }

                // 本来源胜出：重建字段，按裁决全集重排所有来源去向（胜者集合 / 落败集合）
                Map<String, String> winners = new LinkedHashMap<>();
                Map<String, String> losers = new LinkedHashMap<>();
                universe.forEach((s, t) -> {
                    if (t == winner) {
                        winners.put(s, universeRaw.get(s));
                    } else {
                        losers.put(s, universeRaw.get(s));
                    }
                });
                boolean flipped = prior.getCanonicalType() != winner;
                FieldDef rebuilt = new FieldDef(name, winner, winners, losers, prior.isDeprecated());
                pendingFields.put(name, rebuilt);
                addedSourceOnField = true;
                if (flipped) {
                    displacedSources.addAll(prior.getSources().keySet());
                }
                String basis = flipped
                        ? "按内容裁决（规范类型枚举名字典序最小）胜出类型由 "
                                + prior.getCanonicalType() + " 变更为 " + winner
                                + "；旧胜出来源 " + new ArrayList<>(prior.getSources().keySet())
                                + " 转入落败集合并以 TYPE_CONFLICT 追溯，不以到达顺序为准"
                        : "来源 " + source + " 并入胜出来源集合，规范类型保持 " + winner;
                fieldResults[i] = new FieldResult(name, Decision.MERGED, RejectReason.NONE, basis);
            }

            if (anyRejected) {
                // 整批拒绝：结构性写动作（新建字段 / 并入 / 翻转）全部丢弃，规范定义不被污染；
                // 仅把“合法但落败”的台账追加到既有字段（不新建实体、不改规范类型与胜出集合）。
                if (!isNew) {
                    pendingLoserLedgers.values().forEach(entity::putField);
                    entity.reconcileSources();
                }
                return finishRejected(qn, validClaimsMarkedRejected(fieldResults, canonicalNames,
                        RejectReason.TYPE_CONFLICT), source,
                        "存在与内容裁决冲突的字段声明，整批登记被拒绝；规范定义不写入，落败声明仅登记去向",
                        RejectReason.TYPE_CONFLICT);
            }

            // 全部裁决通过：原子落库（此路径下不存在落败台账，所有声明均胜出）
            pendingFields.values().forEach(entity::putField);
            entity.addSource(source);
            entity.reconcileSources();
            if (isNew) {
                store.putEntity(entity);
            }
            // 翻转导致旧胜出来源转入落败集合：为每个被迁移来源补一条 TYPE_CONFLICT 审计，
            // 去向变化与裁决依据逐条可追溯（不改变本次整体 ACCEPTED/MERGED 结论）
            for (String displaced : displacedSources) {
                store.appendAudit(ActionType.REGISTER_ENTITY, displaced, qn,
                        Decision.REJECTED, RejectReason.TYPE_CONFLICT,
                        "来源 " + displaced + " 对字段的声明在内容裁决中落败（规范类型枚举名字典序最小），"
                                + "由胜出集合转入落败台账；触发来源=" + source);
            }

            Decision decision;
            String message;
            if (isNew) {
                decision = Decision.ACCEPTED;
                message = "新实体登记成功";
            } else if (addedField || addedSourceOnField) {
                decision = Decision.MERGED;
                message = "与既有实体合并：" + (addedField ? "含新增字段；" : "")
                        + (addedSourceOnField ? "含字段来源收敛（source=" + source + "）" : "");
            } else {
                decision = Decision.NO_CHANGE;
                message = "声明与既有收敛结论完全一致，幂等返回，未产生任何变化";
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

    /** 整批被拒时，尚未给出逐条结论的合法声明也要给出 REJECTED 结论（附整批主因）。 */
    private static List<FieldResult> validClaimsMarkedRejected(FieldResult[] fieldResults,
                                                               String[] canonicalNames,
                                                               RejectReason batchReason) {
        List<FieldResult> all = new ArrayList<>();
        for (int i = 0; i < fieldResults.length; i++) {
            if (fieldResults[i] != null) {
                all.add(fieldResults[i]);
            } else {
                all.add(new FieldResult(canonicalNames[i], Decision.REJECTED, batchReason,
                        "本条声明本身合法，但因同批次存在非法/冲突声明，随整批拒绝且不落库"));
            }
        }
        return all;
    }

    private RegistrationResult finishRejected(String qn, List<FieldResult> fieldResults, String source,
                                              String message, @Nullable RejectReason topReason) {
        RejectReason reason = topReason != null ? topReason : primaryReason(fieldResults);
        RegistrationResult result = new RegistrationResult(qn, Decision.REJECTED,
                reason, message, fieldResults);
        store.appendAudit(ActionType.REGISTER_ENTITY, source, qn, Decision.REJECTED, reason,
                message + " | 字段级结论=" + fieldResults);
        log.info("[REGISTER] 结论 qualifiedName={}, decision=REJECTED, reason={}, 依据={} | 字段级结论={}",
                qn, reason, message, fieldResults);
        return result;
    }

    private RegistrationResult rejectGlobal(RegistrationRequest request, RejectReason reason,
                                            String message, List<FieldResult> fieldResults) {
        String qn = Names.canonical(request == null ? null : request.qualifiedName());
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
