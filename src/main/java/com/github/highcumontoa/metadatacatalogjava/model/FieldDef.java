package com.github.highcumontoa.metadatacatalogjava.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一模型中的字段定义。
 * declaredType 保留各来源声称的原始类型；canonicalType 为归一后的规范类型；
 * sources 记录声明该字段的来源，值为该来源声称的原始类型。
 */
public final class FieldDef {
    private final String name;
    private final DataType canonicalType;
    private final Map<String, String> sources = new LinkedHashMap<>();
    private final boolean deprecated;

    public FieldDef(String name, DataType canonicalType, Map<String, String> sources, boolean deprecated) {
        this.name = name;
        this.canonicalType = canonicalType;
        if (sources != null) {
            this.sources.putAll(sources);
        }
        this.deprecated = deprecated;
    }

    public String getName() { return name; }
    public DataType getCanonicalType() { return canonicalType; }
    public Map<String, String> getSources() { return Collections.unmodifiableMap(sources); }
    public boolean isDeprecated() { return deprecated; }

    public FieldDef withSource(String source, String rawType) {
        Map<String, String> merged = new LinkedHashMap<>(sources);
        merged.put(source, rawType);
        return new FieldDef(name, canonicalType, merged, deprecated);
    }
}
