package com.github.highcumontoa.metadatacatalogjava.model;

/**
 * 某个来源在某个实体上的最终去向（从实体最终状态确定性推导，
 * 与声明到达顺序、并发调度无关）。
 */
public enum SourceDisposition {
    /** 合并生效：至少一条声明当前生效（或成功登记且无任何声明被取代）。 */
    EFFECTIVE,
    /** 被取代：声明在类型裁决中落败（无论先到后被取代、还是后到被拒），
     *  其原始声明可在对应字段的拒绝台账中查询。 */
    SUPERSEDED,
    /** 被拒绝：批次因实体状态原因（种类不符 / 已废弃 / 无权限）被整体拒绝，
     *  声明从未进入裁决；输入级非法批次的拒绝结论见逐条结果与审计日志。 */
    REJECTED
}
