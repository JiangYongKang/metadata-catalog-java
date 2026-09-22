package com.github.highcumontoa.metadatacatalogjava.service;

import com.github.highcumontoa.metadatacatalogjava.model.DataType;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 各来源原始类型 -> 统一规范类型 的字典。
 * 归一规则大小写不敏感；未登记的原始类型返回 empty（调用方按 UNKNOWN_TYPE 拒绝）。
 */
@Component
public class TypeDictionary {

    private static final Map<String, DataType> MAPPING = Map.ofEntries(
            Map.entry("int", DataType.INTEGER),
            Map.entry("integer", DataType.INTEGER),
            Map.entry("bigint", DataType.BIGINT),
            Map.entry("long", DataType.BIGINT),
            Map.entry("decimal", DataType.DECIMAL),
            Map.entry("numeric", DataType.DECIMAL),
            Map.entry("double", DataType.DOUBLE),
            Map.entry("float", DataType.DOUBLE),
            Map.entry("string", DataType.STRING),
            Map.entry("varchar", DataType.STRING),
            Map.entry("text", DataType.STRING),
            Map.entry("boolean", DataType.BOOLEAN),
            Map.entry("bool", DataType.BOOLEAN),
            Map.entry("timestamp", DataType.TIMESTAMP),
            Map.entry("datetime", DataType.TIMESTAMP)
    );

    public Optional<DataType> normalize(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(MAPPING.get(rawType.trim().toLowerCase(Locale.ROOT)));
    }
}
