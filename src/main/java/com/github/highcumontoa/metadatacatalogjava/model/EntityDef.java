package com.github.highcumontoa.metadatacatalogjava.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 统一模型中的实体（表 / 视图 / 流）。
 * 多个来源对同一实体（按限定名归一）的声明合并到同一对象。
 *
 * <p>实体来源清单是<b>派生值</b>而不是独立累积的状态，见 {@link #getSources()}：
 * 只取决于最终的字段级生效声明与实体级声明，与声明到达顺序、并发调度无关——
 * 同一组声明无论先来后到，来源清单必然收敛到同一个集合。</p>
 */
public final class EntityDef {
    private final String qualifiedName;
    private final EntityKind kind;
    private final Map<String, FieldDef> fields = new LinkedHashMap<>();
    /**
     * 实体级声明来源：以零字段批次成功登记（仅声明实体本身）的来源。
     * 不参与序列化；字段级生效来源由 {@link #getSources()} 动态并入。
     */
    private final Set<String> declaredSources = new java.util.LinkedHashSet<>();
    private volatile boolean deprecated;

    public EntityDef(String qualifiedName, EntityKind kind, boolean deprecated) {
        this.qualifiedName = qualifiedName;
        this.kind = kind;
        this.deprecated = deprecated;
    }

    public String getQualifiedName() { return qualifiedName; }
    public EntityKind getKind() { return kind; }
    public Map<String, FieldDef> getFields() { return Collections.unmodifiableMap(fields); }

    /**
     * 实体来源清单（派生值，确定性收敛）：
     * <pre>实体级声明来源 ∪ 所有字段当前生效来源</pre>
     * 最终生效、参与合并的来源必然在清单中；被裁决取代或被拒绝的来源
     * 不在清单中，其去向可从对应字段的 {@code rejectedClaims} 拒绝台账与审计轨迹查询。
     * 返回按字典序排序的集合，与字段落库先后无关，保证输出稳定。
     */
    public Set<String> getSources() {
        Set<String> all = new TreeSet<>(declaredSources);
        for (FieldDef field : fields.values()) {
            all.addAll(field.getSources().keySet());
        }
        return Collections.unmodifiableSet(all);
    }

    public boolean isDeprecated() { return deprecated; }

    /** 记录一个实体级声明来源（零字段批次被接受）；字段级生效来源无需调用本方法。 */
    public void addDeclaredSource(String source) { declaredSources.add(source); }
    public void putField(FieldDef field) { fields.put(field.getName(), field); }
    public void markDeprecated() { this.deprecated = true; }
}
