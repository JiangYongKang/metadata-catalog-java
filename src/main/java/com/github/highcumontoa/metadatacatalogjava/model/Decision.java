package com.github.highcumontoa.metadatacatalogjava.model;

/** 登记 / 变更 / 血缘等操作的统一结论分类。 */
public enum Decision {
    /** 全新写入成功 */
    ACCEPTED,
    /** 与既有数据合并成功（例如补充了新字段或新来源） */
    MERGED,
    /** 幂等：输入与既有定义完全一致，未产生任何变化 */
    NO_CHANGE,
    /** 被拒绝，原因见 RejectReason */
    REJECTED
}
