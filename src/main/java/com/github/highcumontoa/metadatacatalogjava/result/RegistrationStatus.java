package com.github.highcumontoa.metadatacatalogjava.result;

/**
 * Outcome status of a registration.
 */
public enum RegistrationStatus {
    /** Brand-new entity registered. */
    REGISTERED,
    /** Declaration merged into an existing entity with new information. */
    MERGED,
    /** Declaration identical to what is already known; no state change. */
    DUPLICATE,
    /** Declaration merged, but at least one field has conflicting source claims. */
    CONFLICT,
    /** Declaration rejected; see RejectReason and message. */
    REJECTED
}
