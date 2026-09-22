package com.github.highcumontoa.metadatacatalogjava.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A declaration of one entity as reported by a single metadata source.
 *
 * @param source  source identifier (e.g. "hive", "kafka", "mysql")
 * @param entity  declared entity name
 * @param kind    declared entity kind ("table"/"topic"/"file"/"api"), may be null
 * @param fields  declared fields
 */
public record SourceDeclaration(
        String source,
        String entity,
        String kind,
        List<FieldDeclaration> fields) {

    /**
     * One field as declared by a source. Raw types are kept verbatim
     * until normalization.
     */
    public record FieldDeclaration(String name,
                                        @JsonProperty("type") String rawType,
                                        boolean nullable) {
    }
}
