package com.github.highcumontoa.metadatacatalogjava.model;

import java.util.Locale;
import java.util.Map;

/**
 * Unified (canonical) data types used across all sources.
 * Each source-specific type spelling is mapped to one canonical type.
 */
public enum DataType {
    STRING,
    INTEGER,
    LONG,
    DECIMAL,
    BOOLEAN,
    DATE,
    TIMESTAMP,
    BINARY,
    /** Sentinel marking a field whose sources disagree on the type. */
    UNKNOWN;

    private static final Map<String, DataType> ALIASES = Map.ofEntries(
            Map.entry("string", STRING),
            Map.entry("varchar", STRING),
            Map.entry("char", STRING),
            Map.entry("text", STRING),
            Map.entry("nvarchar", STRING),
            Map.entry("int", INTEGER),
            Map.entry("integer", INTEGER),
            Map.entry("smallint", INTEGER),
            Map.entry("tinyint", INTEGER),
            Map.entry("bigint", LONG),
            Map.entry("long", LONG),
            Map.entry("decimal", DECIMAL),
            Map.entry("numeric", DECIMAL),
            Map.entry("number", DECIMAL),
            Map.entry("double", DECIMAL),
            Map.entry("float", DECIMAL),
            Map.entry("boolean", BOOLEAN),
            Map.entry("bool", BOOLEAN),
            Map.entry("date", DATE),
            Map.entry("timestamp", TIMESTAMP),
            Map.entry("datetime", TIMESTAMP),
            Map.entry("binary", BINARY),
            Map.entry("bytes", BINARY),
            Map.entry("varbinary", BINARY));

    /**
     * Parse a source-declared type into a canonical type.
     *
     * @return the canonical type, or {@link #UNKNOWN} when the text is not a legal type.
     */
    public static DataType parse(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        // strip parameter lengths like varchar(255), decimal(10,2)
        int paren = key.indexOf('(');
        if (paren >= 0) {
            key = key.substring(0, paren).trim();
        }
        return ALIASES.getOrDefault(key, UNKNOWN);
    }

    public static boolean isLegal(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        return parse(raw) != UNKNOWN;
    }
}
