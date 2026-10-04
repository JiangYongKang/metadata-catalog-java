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
    /** 同名字段与既有规范类型冲突 */
    TYPE_CONFLICT,
    /** 同一实体名被不同来源声明为不同种类（如 TABLE vs VIEW），拒绝静默合并 */
    KIND_CONFLICT,
    /** 同一批次内同名字段声明了不同类型（彼此互斥），区别于整字段重名 */
    FIELD_CONFLICT_IN_PAYLOAD,
    /** 实体存在但被操作的字段不存在（与 UNKNOWN_ENTITY 明确区分） */
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
