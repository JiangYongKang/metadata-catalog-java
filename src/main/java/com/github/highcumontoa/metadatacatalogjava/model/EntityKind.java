package com.github.highcumontoa.metadatacatalogjava.model;

import java.util.Locale;

/**
 * Kind of a registered metadata entity.
 */
public enum EntityKind {
    TABLE,
    TOPIC,
    FILE,
    API;

    public static EntityKind parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}
