package com.agent.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** 描述一组任务必须证明的 Agent 能力和质量阈值。 */
public record EvaluationCapability(
        String id,
        String chapter,
        List<String> requiredTrace,
        double minPassK,
        Duration maxTtftP95,
        BigDecimal maxCostUsd) {

    public EvaluationCapability {
        id = requireText(id, "id");
        chapter = requireText(chapter, "chapter");
        requiredTrace = List.copyOf(Objects.requireNonNull(
                requiredTrace, "requiredTrace 不能为空"));
        if (requiredTrace.isEmpty()
                || requiredTrace.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("requiredTrace 必须包含非空事件名");
        }
        if (!Double.isFinite(minPassK) || minPassK < 0 || minPassK > 1) {
            throw new IllegalArgumentException("minPassK 必须位于 0 到 1 之间");
        }
        maxTtftP95 = requirePositive(maxTtftP95, "maxTtftP95");
        maxCostUsd = normalizePositive(maxCostUsd, "maxCostUsd");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " 不能为空");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " 必须大于 0");
        }
        return value;
    }

    private static BigDecimal normalizePositive(BigDecimal value, String name) {
        Objects.requireNonNull(value, name + " 不能为空");
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + " 必须大于 0");
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }
}
