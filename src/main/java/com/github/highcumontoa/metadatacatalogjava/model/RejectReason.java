package com.github.highcumontoa.metadatacatalogjava.model;

/** 拒绝原因分类，结论必须可区分。 */
public enum RejectReason {
    NONE,
    /** 请求体缺少实体名等必填信息 */
    INVALID_PAYLOAD,
    /** 字段缺少名称 */
    MISSING_FIELD_NAME,
    /** 来源原始类型不在类型字典中，无法归一 */
    UNKNOWN_TYPE,
    /** 同名字段与既有规范类型冲突（按 TypeArbitrator 固定优先级裁决后落败） */
    TYPE_CONFLICT,
    /** 同名实体被不同来源声明为不同种类（如 TABLE vs VIEW），拒绝合并 */
    KIND_CONFLICT,
    /** 目标字段不存在（与实体不存在 UNKNOWN_ENTITY 明确区分） */
    UNKNOWN_FIELD,
    /** 实体已废弃，拒绝一切写操作 */
    ENTITY_DEPRECATED,
    /** 操作者对该实体无修改权限 */
    PERMISSION_DENIED,
    /** 目标实体不存在 */
    UNKNOWN_ENTITY,
    /** 血缘上下游为同一实体（自引用） */
    SELF_REFERENCE,
    /** 登记该血缘会形成环 */
    CYCLE_DETECTED,
    /** 删除字段属于破坏性变更 */
    FIELD_REMOVED_INCOMPATIBLE,
    /** 类型变更不属于允许的放宽集合 */
    TYPE_CHANGE_INCOMPATIBLE,
    /** 同一批次内存在重复字段等冲突 */
    DUPLICATE_FIELD_IN_PAYLOAD
}
