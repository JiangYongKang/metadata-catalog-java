package com.github.highcumontoa.metadatacatalogjava.result;

public record LineageResult(
        LineageStatus status,
        RejectReason rejectReason,
        String message,
        String upstream,
        String downstream) {

    public static LineageResult rejected(RejectReason reason, String message, String up, String down) {
        return new LineageResult(LineageStatus.REJECTED, reason, message, up, down);
    }
}
