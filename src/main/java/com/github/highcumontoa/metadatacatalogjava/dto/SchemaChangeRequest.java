package com.github.highcumontoa.metadatacatalogjava.dto;

import java.util.List;

/**
 * 对某实体发起的字段变更：可包含多个 ADD / DROP / ALTER_TYPE 操作。
 * 破坏性变更将按统一兼容策略拒绝并逐字段说明原因。
 */
public record SchemaChangeRequest(
        String operator,
        String qualifiedName,
        List<ChangeFieldOp> ops
) {
}
