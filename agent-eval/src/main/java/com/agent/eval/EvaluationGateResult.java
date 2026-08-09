package com.agent.eval;

import java.util.List;
import java.util.Objects;

/** CI 门禁的不可变判定和阈值违反证据。 */
public record EvaluationGateResult(boolean passed, List<Violation> violations) {

    public EvaluationGateResult {
        violations = List.copyOf(Objects.requireNonNull(violations, "violations 不能为空"));
        if (violations.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("violations 不能包含 null");
        }
        if (passed != violations.isEmpty()) {
            throw new IllegalArgumentException("passed 必须与 violations 是否为空一致");
        }
    }

    /** 单个指标越界的脱敏证据。 */
    public record Violation(String metric, String actual, String limit, String message) {

        public Violation {
            metric = requireText(metric, "metric");
            actual = requireText(actual, "actual");
            limit = requireText(limit, "limit");
            message = requireText(message, "message");
        }

        private static String requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " 不能为空");
            }
            return value;
        }
    }
}
