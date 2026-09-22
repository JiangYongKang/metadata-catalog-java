package com.github.highcumontoa.metadatacatalogjava.dto;

import com.github.highcumontoa.metadatacatalogjava.model.Decision;
import com.github.highcumontoa.metadatacatalogjava.model.RejectReason;

import java.util.List;

/** 字段变更的整体结论与逐操作结论；任一破坏性操作存在则整体 REJECTED 且不生效。 */
public record SchemaChangeResult(
        String qualifiedName,
        Decision decision,
        RejectReason reason,
        String message,
        List<FieldResult> opResults
) {
}
