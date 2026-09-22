package com.github.highcumontoa.metadatacatalogjava.model;

import java.time.Instant;

/**
 * Append-only audit trail entry. Both successful changes and rejections are recorded.
 *
 * @param seq     monotonically increasing sequence number
 * @param at      timestamp
 * @param action  action name (REGISTER/ADD_LINEAGE/ADD_FIELD/...)
 * @param target  target entity qualified name (or edge description)
 * @param actor   who attempted the action
 * @param allowed whether the action was applied
 * @param reason  reject reason code when not allowed, otherwise outcome code
 * @param detail  human-readable basis for the decision
 */
public record AuditRecord(
        long seq,
        Instant at,
        String action,
        String target,
        String actor,
        boolean allowed,
        String reason,
        String detail) {
}
