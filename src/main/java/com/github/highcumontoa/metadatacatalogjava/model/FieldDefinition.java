package com.github.highcumontoa.metadatacatalogjava.model;

import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Canonical field (column/attribute) definition.
 *
 * @param name          canonical (normalized) field name
 * @param displayName   first-seen spelling of the name
 * @param dataType      canonical type; UNKNOWN marks a conflicted field
 * @param nullable      whether the field is nullable (OR of source claims)
 * @param sources       sources that declared this field
 * @param declaredTypes raw type strings declared by each source
 * @param conflicted    whether sources disagree on the canonical type
 */
public record FieldDefinition(
        String name,
        String displayName,
        DataType dataType,
        boolean nullable,
        Set<String> sources,
        SortedMap<String, String> declaredTypes,
        boolean conflicted) {

    /**
     * Return a copy with the conflict marker and (possibly) canonical type replaced.
     * All collections are defensively copied so prior snapshots stay immutable.
     */
    public FieldDefinition withConflict(boolean conflicted, DataType type) {
        SortedMap<String, String> dt = new TreeMap<>(declaredTypes);
        Set<String> src = new TreeSet<>(sources);
        return new FieldDefinition(name, displayName, type, nullable, src, dt, conflicted);
    }
}
