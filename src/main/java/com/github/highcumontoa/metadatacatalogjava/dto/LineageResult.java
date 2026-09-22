package com.github.highcumontoa.metadatacatalogjava.dto;

import com.github.highcumontoa.metadatacatalogjava.model.Decision;
import com.github.highcumontoa.metadatacatalogjava.model.RejectReason;

/** 血缘登记结论。NO_CHANGE 表示该血缘此前已登记（幂等，不重复计数）。 */
public record LineageResult(
        String upstream,
        String downstream,
        Decision decision,
        RejectReason reason,
        String message
) {
}
