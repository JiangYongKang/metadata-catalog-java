package com.github.highcumontoa.metadatacatalogjava.dto;

import com.github.highcumontoa.metadatacatalogjava.model.Decision;
import com.github.highcumontoa.metadatacatalogjava.model.RejectReason;

import java.util.List;

/** 实体登记的整体结论与逐字段结论。 */
public record RegistrationResult(
        String qualifiedName,
        Decision decision,
        RejectReason reason,
        String message,
        List<FieldResult> fieldResults
) {
}
