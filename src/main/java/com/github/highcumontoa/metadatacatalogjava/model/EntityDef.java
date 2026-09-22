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
    public void putField(FieldDef field) { fields.put(field.getName(), field); }
    public void markDeprecated() { this.deprecated = true; }
}
