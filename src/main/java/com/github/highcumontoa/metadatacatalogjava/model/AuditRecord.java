package com.github.highcumontoa.metadatacatalogjava.model;

import java.time.Instant;

/** 只追加的审计记录，所有被接受 / 拒绝的写操作均留痕。 */
public record AuditRecord(
        long sequence,
        Instant timestamp,
        ActionType action,
        String operator,
        String targetEntity,
        Decision decision,
        RejectReason reason,
        String detail
) {
}
