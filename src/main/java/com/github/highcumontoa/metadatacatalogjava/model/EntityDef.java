package com.github.highcumontoa.metadatacatalogjava.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 统一模型中的实体（表 / 视图 / 流）。
 * 多个来源对同一实体（按限定名归一）的声明合并到同一对象。
 *
 * <p>来源清单与每个来源的最终去向不随声明到达顺序 / 并发调度变化：
 * 它们是从实体最终状态（各字段生效声明、拒绝台账、登记 / 拒绝标记）
 * 确定性推导出来的，而不是按到达先后增量叠加的：</p>
 * <ul>
 *   <li>{@link #getSources()} 只含最终生效（{@link SourceDisposition#EFFECTIVE}）的来源，
 *       按来源名排序，序列化结果稳定；</li>
 *   <li>{@link #getSourceOutcomes()} 给出每个已知来源的最终去向
 *       （合并生效 / 被取代 / 被拒绝），判定依据见 {@link SourceDisposition}。</li>
 * </ul>
 */
public final class EntityDef {
    private final String qualifiedName;
    private final EntityKind kind;
    private final Map<String, FieldDef> fields = new LinkedHashMap<>();
    /** 至少成功登记过一次的来源（含零字段登记）。 */
    private final Set<String> registeredSources = new LinkedHashSet<>();
    /** 批次因实体状态原因（种类不符 / 已废弃 / 无权限）被整体拒绝的来源。 */
    private final Set<String> rejectedSources = new LinkedHashSet<>();
    private volatile boolean deprecated;

    public EntityDef(String qualifiedName, EntityKind kind, boolean deprecated) {
        this.qualifiedName = qualifiedName;
        this.kind = kind;
        this.deprecated = deprecated;
    }

    public String getQualifiedName() { return qualifiedName; }
    public EntityKind getKind() { return kind; }

    /** 字段定义按字段名排序返回：序列化 / 展示结果与声明到达顺序无关。 */
    public Map<String, FieldDef> getFields() {
        return Collections.unmodifiableMap(new TreeMap<>(fields));
    }

    public boolean isDeprecated() { return deprecated; }

    /**
     * 实体的来源清单：最终生效（参与合并）的来源，按来源名排序。
     * 被取代 / 被拒绝的来源不在清单中，其去向见 {@link #getSourceOutcomes()}。
     */
    public Set<String> getSources() {
        Set<String> effective = new TreeSet<>();
        getSourceOutcomes().forEach((source, disposition) -> {
            if (disposition == SourceDisposition.EFFECTIVE) {
                effective.add(source);
            }
        });
        return Collections.unmodifiableSet(effective);
    }

    /**
     * 每个来源的最终去向（按来源名排序，确定性推导）：
     * <ol>
     *   <li>任一字段的生效声明中含该来源 -&gt; EFFECTIVE；</li>
     *   <li>否则任一字段的拒绝台账中含该来源 -&gt; SUPERSEDED；</li>
     *   <li>否则成功登记过（声明未被取代，如零字段登记） -&gt; EFFECTIVE；</li>
     *   <li>否则（仅有批次级拒绝记录） -&gt; REJECTED。</li>
     * </ol>
     * 推导只依赖实体最终状态，因此与声明到达顺序、并发调度无关。
     */
    public Map<String, SourceDisposition> getSourceOutcomes() {
        Set<String> effectiveClaims = new TreeSet<>();
        Set<String> supersededClaims = new TreeSet<>();
        for (FieldDef field : fields.values()) {
            effectiveClaims.addAll(field.getSources().keySet());
            supersededClaims.addAll(field.getRejectedClaims().keySet());
        }
        Set<String> known = new TreeSet<>(effectiveClaims);
        known.addAll(supersededClaims);
        known.addAll(registeredSources);
        known.addAll(rejectedSources);

        Map<String, SourceDisposition> outcomes = new TreeMap<>();
        for (String source : known) {
            if (effectiveClaims.contains(source)) {
                outcomes.put(source, SourceDisposition.EFFECTIVE);
            } else if (supersededClaims.contains(source)) {
                outcomes.put(source, SourceDisposition.SUPERSEDED);
            } else if (registeredSources.contains(source)) {
                outcomes.put(source, SourceDisposition.EFFECTIVE);
            } else {
                outcomes.put(source, SourceDisposition.REJECTED);
            }
        }
        return Collections.unmodifiableMap(outcomes);
    }

    /** 记录一次成功登记（含零字段登记）；需在实体锁内调用。 */
    public void markRegistered(String source) {
        registeredSources.add(source);
    }

    /** 记录一次批次级拒绝（种类不符 / 已废弃 / 无权限）；需在实体锁内调用。 */
    public void markRejected(String source) {
        rejectedSources.add(source);
    }

    public void putField(FieldDef field) { fields.put(field.getName(), field); }
    public void markDeprecated() { this.deprecated = true; }
}
