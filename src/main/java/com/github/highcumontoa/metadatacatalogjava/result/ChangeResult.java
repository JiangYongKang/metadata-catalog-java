package com.github.highcumontoa.metadatacatalogjava.result;

import com.github.highcumontoa.metadatacatalogjava.model.EntityDefinition;

public record ChangeResult(
        ChangeStatus status,
        RejectReason rejectReason,
        String message,
        EntityDefinition before,
        EntityDefinition after) {

    public static ChangeResult rejected(RejectReason reason, String message, EntityDefinition before) {
        return new ChangeResult(ChangeStatus.REJECTED, reason, message, before, before);
    }
}
