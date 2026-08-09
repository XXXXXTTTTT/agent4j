package com.agent.eval;

import java.util.List;
import java.util.Objects;

/** 对节点和工具事件执行大小写敏感的有序子序列评分。 */
public final class EvaluationTraceScorer {

    private EvaluationTraceScorer() {
    }

    /** requiredTrace 为空时通过，否则每个事件必须按顺序出现在实际轨迹中。 */
    public static boolean containsInOrder(List<String> actual, List<String> required) {
        Objects.requireNonNull(actual, "actual 不能为空");
        Objects.requireNonNull(required, "required 不能为空");
        int requiredIndex = 0;
        for (String event : actual) {
            if (event == null) {
                throw new IllegalArgumentException("actual 不能包含 null");
            }
            if (requiredIndex < required.size()
                    && Objects.equals(event, required.get(requiredIndex))) {
                requiredIndex++;
            }
        }
        return requiredIndex == required.size();
    }
}
