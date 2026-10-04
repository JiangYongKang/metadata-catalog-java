package com.github.highcumontoa.metadatacatalogjava.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一模型中的实体（表 / 视图 / 流）。
 * 多个来源对同一实体（按限定名归一）的声明合并到同一对象。
 */
public final class EntityDef {
    private final String qualifiedName;
    private final EntityKind kind;
    private final Map<String, FieldDef> fields = new LinkedHashMap<>();
    private final java.util.Set<String> sources = new java.util.LinkedHashSet<>();
    private volatile boolean deprecated;

    public EntityDef(String qualifiedName, EntityKind kind, boolean deprecated) {
        this.qualifiedName = qualifiedName;
        this.kind = kind;
        this.deprecated = deprecated;
    }

    public String getQualifiedName() { return qualifiedName; }
    public EntityKind getKind() { return kind; }
    public Map<String, FieldDef> getFields() { return Collections.unmodifiableMap(fields); }
    public java.util.Set<String> getSources() { return Collections.unmodifiableSet(sources); }
    public boolean isDeprecated() { return deprecated; }

    public void addSource(String source) { sources.add(source); }
    public void removeSource(String source) { sources.remove(source); }
    public void putField(FieldDef field) { fields.put(field.getName(), field); }
    public void markDeprecated() { this.deprecated = true; }

    /**
     * 按字段最终裁决结果收敛实体级来源集合：若某来源在其参与过的全部字段上均已落败，
     * 则从实体来源集合移除（只在声明空字段集、未参与任何字段裁决的来源被保留）。
     * 收敛结果只取决于字段胜出/落败集合，与请求到达顺序无关。
     */
    public void reconcileSources() {
        java.util.Set<String> winners = new java.util.HashSet<>();
        java.util.Set<String> losers = new java.util.HashSet<>();
        for (FieldDef f : fields.values()) {
            winners.addAll(f.getSources().keySet());
            losers.addAll(f.getLosingClaims().keySet());
        }
        sources.removeIf(s -> losers.contains(s) && !winners.contains(s));
    }
}
