package com.github.highcumontoa.metadatacatalogjava.dto;

/** 字段级变更操作：ADD / DROP / ALTER_TYPE。 */
public record ChangeFieldOp(String op, String fieldName, String rawType) {
}
