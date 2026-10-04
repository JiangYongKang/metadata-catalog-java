package com.github.highcumontoa.metadatacatalogjava.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一模型中的字段定义。
 *
 * <p>{@code sources} 记录当前胜出（与规范类型一致）的来源及其声称的原始类型；
 * {@code losingClaims} 记录在内容裁决中落败的来源最后一次声称的原始类型——
 * 保留落败声明是为了让裁决始终在“该字段的全部已登记声明”这一全集上重算，
 * 保证最终规范类型、胜出/落败来源去向与请求到达顺序、线程调度无关。</p>
 */
public final class FieldDef {
    private final String name;
    private final DataType canonicalType;
    private final Map<String, String> sources = new LinkedHashMap<>();
    private final Map<String, String> losingClaims = new LinkedHashMap<>();
    private final boolean deprecated;

    public FieldDef(String name, DataType canonicalType, Map<String, String> sources, boolean deprecated) {
        this(name, canonicalType, sources, Map.of(), deprecated);
    }

    public FieldDef(String name, DataType canonicalType, Map<String, String> sources,
                    Map<String, String> losingClaims, boolean deprecated) {
        this.name = name;
        this.canonicalType = canonicalType;
        if (sources != null) {
            this.sources.putAll(sources);
        }
        if (losingClaims != null) {
            this.losingClaims.putAll(losingClaims);
        }
        this.deprecated = deprecated;
    }

    public String getName() { return name; }
    public DataType getCanonicalType() { return canonicalType; }
    /** 胜出来源（其声明与当前规范类型一致）-> 该来源声称的原始类型。 */
    public Map<String, String> getSources() { return Collections.unmodifiableMap(sources); }
    /** 落败来源 -> 该来源最后一次声称的原始类型（保留用于顺序无关的重裁决与去向追溯）。 */
    public Map<String, String> getLosingClaims() { return Collections.unmodifiableMap(losingClaims); }
    public boolean isDeprecated() { return deprecated; }

    public FieldDef withSource(String source, String rawType) {
        Map<String, String> merged = new LinkedHashMap<>(sources);
        merged.put(source, rawType);
        return new FieldDef(name, canonicalType, merged, losingClaims, deprecated);
    }
}
