package com.github.highcumontoa.metadatacatalogjava.dto;

import com.github.highcumontoa.metadatacatalogjava.model.Decision;
import com.github.highcumontoa.metadatacatalogjava.model.RejectReason;

/** 单个字段 / 单个变更操作的逐条结论，保证可解释。 */
public record FieldResult(String fieldName, Decision decision, RejectReason reason, String detail) {
}
