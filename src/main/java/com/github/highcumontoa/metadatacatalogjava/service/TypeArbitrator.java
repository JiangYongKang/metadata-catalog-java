package com.github.highcumontoa.metadatacatalogjava.service;

import com.github.highcumontoa.metadatacatalogjava.model.DataType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 同名字段出现多种规范类型声明时的确定性裁决规则。
 *
 * <p>裁决依据：规范类型的固定优先级（按精度与表达能力定义的全序），
 * 与声明到达先后、并发调度完全无关——同一组声明无论先来后到，
 * 胜出的规范类型与每个来源的去向都一致，绝不是"谁先到谁说了算"。</p>
 *
 * <p>优先级（高 -&gt; 低）：DECIMAL &gt; DOUBLE &gt; BIGINT &gt; INTEGER
 * &gt; TIMESTAMP &gt; STRING &gt; BOOLEAN。</p>
 *
 * <p>裁决结果：</p>
 * <ul>
 *   <li>新声明优先级高于既有规范类型 -&gt; 新声明胜出，规范类型被取代，
 *       既有来源的声明转入该字段的拒绝台账（去向可查询）；</li>
 *   <li>新声明优先级低于既有规范类型 -&gt; 新声明以 TYPE_CONFLICT 拒绝，
 *       并记入拒绝台账。</li>
 * </ul>
 */
@Component
public class TypeArbitrator {

    private static final Map<DataType, Integer> PRIORITY = new EnumMap<>(DataType.class);

    static {
        PRIORITY.put(DataType.DECIMAL, 60);
        PRIORITY.put(DataType.DOUBLE, 50);
        PRIORITY.put(DataType.BIGINT, 40);
        PRIORITY.put(DataType.INTEGER, 30);
        PRIORITY.put(DataType.TIMESTAMP, 20);
        PRIORITY.put(DataType.STRING, 10);
        PRIORITY.put(DataType.BOOLEAN, 0);
    }

    /** 类型的裁决优先级，数值越大优先级越高。 */
    public int priority(DataType type) {
        return PRIORITY.get(type);
    }

    /**
     * 裁决两个规范类型的胜负。
     *
     * @return true 表示 challenger 胜出（应取代 incumbent）
     */
    public boolean challengerWins(DataType challenger, DataType incumbent) {
        return priority(challenger) > priority(incumbent);
    }

    /** 裁决依据的文字说明，写入结论与日志，保证可解释。 */
    public String explain(DataType challenger, DataType incumbent) {
        return "按规范类型固定优先级裁决（" + challenger + "=" + priority(challenger)
                + " vs " + incumbent + "=" + priority(incumbent)
                + "，与到达顺序无关）";
    }
}
