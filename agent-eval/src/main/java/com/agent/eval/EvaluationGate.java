package com.agent.eval;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 根据报告中冻结的策略计算稳定 CI 门禁。 */
public final class EvaluationGate {

    private EvaluationGate() {
    }

    public static EvaluationGateResult evaluate(EvaluationReport report) {
        Objects.requireNonNull(report, "report 不能为空");
        EvaluationGatePolicy policy = report.policy();
        List<EvaluationGateResult.Violation> violations = new ArrayList<>();
        double passK = report.benchmarkReport().passK();
        if (passK < policy.minPassK()) {
            violations.add(violation("passK", decimal(passK), decimal(policy.minPassK()),
                    "整体 passK 低于门禁"));
        }
        double ttftP95 = report.benchmarkReport().ttft().p95Ms();
        double maxTtftP95 = millis(policy.maxTtftP95());
        if (ttftP95 > maxTtftP95) {
            violations.add(violation("ttftP95", decimal(ttftP95), decimal(maxTtftP95),
                    "TTFT P95 超出门禁"));
        }
        if (report.totalCostUsd().compareTo(policy.maxCostUsd()) > 0) {
            violations.add(violation("costUsd", report.totalCostUsd().toPlainString(),
                    policy.maxCostUsd().toPlainString(), "总费用超出门禁"));
        }
        int failureCount = report.failureCounts().entrySet().stream()
                .filter(entry -> entry.getKey() != FailureCategory.NONE)
                .mapToInt(java.util.Map.Entry::getValue)
                .sum();
        if (failureCount > policy.maxFailureCount()) {
            violations.add(violation("failureCount", Integer.toString(failureCount),
                    Integer.toString(policy.maxFailureCount()), "失败观测数超出门禁"));
        }
        for (EvaluationReport.CapabilityMetrics capability : report.capabilities()) {
            if (capability.passK() < capability.requiredMinPassK()) {
                violations.add(violation(
                        "capability." + capability.capabilityId() + ".passK",
                        decimal(capability.passK()), decimal(capability.requiredMinPassK()),
                        "能力 passK 低于门禁"));
            }
            if (capability.ttftP95().compareTo(capability.maxTtftP95()) > 0) {
                violations.add(violation(
                        "capability." + capability.capabilityId() + ".ttftP95",
                        decimal(millis(capability.ttftP95())),
                        decimal(millis(capability.maxTtftP95())),
                        "能力 TTFT P95 超出门禁"));
            }
        }
        return new EvaluationGateResult(violations.isEmpty(), violations);
    }

    /** 门禁失败时抛出不含任务正文的强类型异常。 */
    public static void assertPassed(EvaluationReport report) {
        EvaluationGateResult result = evaluate(report);
        if (!result.passed()) {
            throw new EvaluationGateViolationException(result);
        }
    }

    private static EvaluationGateResult.Violation violation(
            String metric, String actual, String limit, String message) {
        return new EvaluationGateResult.Violation(metric, actual, limit, message);
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static double millis(Duration duration) {
        return duration.toNanos() / 1_000_000.0;
    }
}
