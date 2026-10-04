package com.github.highcumontoa.metadatacatalogjava.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一模型中的字段定义。
 * name 为归一后的字段名；canonicalType 为归一后的规范类型；
 * sources 记录当前生效声明的来源（来源 -> 该来源声称的原始类型）；
 * rejectedClaims 为拒绝台账：记录按 TypeArbitrator 裁决落败的来源及其原始类型，
 * 使"每个来源的去向"可以从最终状态直接查询（在 sources 中 = 生效合并，
 * 在 rejectedClaims 中 = 被 TYPE_CONFLICT 拒绝 / 取代）。台账只记录去向，
 * 不参与规范定义，因此拒绝批次写入台账不视为污染既有数据。
 */
public final class FieldDef {
    private final String name;
    private final DataType canonicalType;
    private final Map<String, String> sources = new LinkedHashMap<>();
    private final Map<String, String> rejectedClaims = new LinkedHashMap<>();
    private final boolean deprecated;

    public FieldDef(String name, DataType canonicalType, Map<String, String> sources, boolean deprecated) {
        this(name, canonicalType, sources, Map.of(), deprecated);
    }

    public FieldDef(String name, DataType canonicalType, Map<String, String> sources,
                    Map<String, String> rejectedClaims, boolean deprecated) {
        this.name = name;
        this.canonicalType = canonicalType;
        if (sources != null) {
            this.sources.putAll(sources);
        }
        if (rejectedClaims != null) {
            this.rejectedClaims.putAll(rejectedClaims);
        }
        this.deprecated = deprecated;
    }

    public String getName() { return name; }
    public DataType getCanonicalType() { return canonicalType; }
    public Map<String, String> getSources() { return Collections.unmodifiableMap(sources); }
    public Map<String, String> getRejectedClaims() { return Collections.unmodifiableMap(rejectedClaims); }
    public boolean isDeprecated() { return deprecated; }

    /** 追加一个生效来源；若该来源此前在拒绝台账中，则移除（最终去向以生效为准）。 */
    public FieldDef withSource(String source, String rawType) {
        Map<String, String> merged = new LinkedHashMap<>(sources);
        merged.put(source, rawType);
        Map<String, String> rejected = new LinkedHashMap<>(rejectedClaims);
        rejected.remove(source);
        return new FieldDef(name, canonicalType, merged, rejected, deprecated);
    }

    /** 记录一个落败来源到拒绝台账；若该来源当前是生效来源则不动台账（生效优先）。 */
    public FieldDef withRejectedClaim(String source, String rawType) {
        if (sources.containsKey(source)) {
            return this;
        }
        Map<String, String> rejected = new LinkedHashMap<>(rejectedClaims);
        rejected.put(source, rawType);
        return new FieldDef(name, canonicalType, sources, rejected, deprecated);
    }

    /**
     * 裁决取代：规范类型变为 winnerType，当前生效来源全部转入拒绝台账，
     * 胜出来源成为唯一生效来源。
     */
    public FieldDef supersededBy(DataType winnerType, String winnerSource, String winnerRawType) {
        Map<String, String> rejected = new LinkedHashMap<>(rejectedClaims);
        sources.forEach(rejected::putIfAbsent);
        Map<String, String> newSources = new LinkedHashMap<>();
        newSources.put(winnerSource, winnerRawType);
        rejected.remove(winnerSource);
        return new FieldDef(name, winnerType, newSources, rejected, deprecated);
    }
}
