package com.github.highcumontoa.metadatacatalogjava.result;

/**
 * Distinguishable rejection reasons.
 */
public enum RejectReason {
    // registration input problems
    BLANK_SOURCE,
    BLANK_ENTITY_NAME,
    BLANK_FIELD_NAME,
    ILLEGAL_FIELD_TYPE,
    KIND_MISMATCH,

    // entity state problems
    ENTITY_NOT_FOUND,
    FIELD_NOT_FOUND,
    ENTITY_DEPRECATED,
    ENTITY_READ_ONLY,

    // schema compatibility
    BREAKING_TYPE_CHANGE,
    BREAKING_FIELD_REMOVAL,

    // lineage problems
    LINEAGE_SELF_REFERENCE,
    LINEAGE_CYCLE,
    LINEAGE_ENDPOINT_NOT_FOUND
}
