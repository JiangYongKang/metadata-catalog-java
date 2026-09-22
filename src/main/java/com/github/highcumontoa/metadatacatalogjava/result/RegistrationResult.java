package com.github.highcumontoa.metadatacatalogjava.result;

import com.github.highcumontoa.metadatacatalogjava.model.EntityDefinition;

/**
 * Result of registering one source declaration.
 */
public record RegistrationResult(
        RegistrationStatus status,
        RejectReason rejectReason,
        String message,
        EntityDefinition entity,
        java.util.List<String> conflictFields) {

    public static RegistrationResult of(RegistrationStatus status, String message,
                                        EntityDefinition entity, java.util.List<String> conflicts) {
        return new RegistrationResult(status, null, message, entity, conflicts);
    }

    public static RegistrationResult rejected(RejectReason reason, String message) {
        return new RegistrationResult(RegistrationStatus.REJECTED, reason, message, null, java.util.List.of());
    }
}
