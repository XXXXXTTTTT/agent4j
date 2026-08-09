package com.agent.eval;

import java.util.stream.Collectors;

/** Evaluation 未满足 CI 门禁时抛出的强类型异常。 */
public final class EvaluationGateViolationException extends IllegalStateException {

    private final EvaluationGateResult result;

    public EvaluationGateViolationException(EvaluationGateResult result) {
        super("Evaluation 门禁失败: " + result.violations().stream()
                .map(violation -> violation.metric() + "=" + violation.actual()
                        + " limit=" + violation.limit())
                .collect(Collectors.joining(", ")));
        this.result = result;
    }

    public EvaluationGateResult result() {
        return result;
    }
}
