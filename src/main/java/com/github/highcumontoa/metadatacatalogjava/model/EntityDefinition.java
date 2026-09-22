package com.github.highcumontoa.metadatacatalogjava.model;

import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Canonical entity (table/topic/file/api) definition merged from source declarations.
 * Instances are immutable snapshots; services produce new snapshots on change.
 */
public record EntityDefinition(
        String qualifiedName,
        String displayName,
        EntityKind kind,
        SortedMap<String, FieldDefinition> fields,
        SortedSet<String> sources,
        boolean deprecated,
        boolean readOnly) {

    public boolean hasField(String fieldName) {
        return fields.containsKey(fieldName);
    }

    /** Snapshot copy with field map replaced. */
    public EntityDefinition withFields(SortedMap<String, FieldDefinition> newFields) {
        return new EntityDefinition(qualifiedName, displayName, kind,
                new TreeMap<>(newFields), new TreeSet<>(sources), deprecated, readOnly);
    }

    /** Snapshot copy with lifecycle flags replaced. */
    public EntityDefinition withFlags(boolean deprecated, boolean readOnly) {
        return new EntityDefinition(qualifiedName, displayName, kind,
                new TreeMap<>(fields), new TreeSet<>(sources), deprecated, readOnly);
    }
}
