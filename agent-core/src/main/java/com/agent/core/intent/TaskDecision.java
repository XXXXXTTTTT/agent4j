package com.agent.core.intent;

import java.util.Objects;
import java.util.Set;

/** 任务路线、类别、复杂度和能力需求的不可变决策。 */
public record TaskDecision(
        TaskRoute route,
        TaskKind taskKind,
        TaskComplexity complexity,
        Set<RequiredCapability> requiredCapabilities,
        String reason) {

    /** 冻结能力集合并拒绝路线与能力矛盾。 */
    public TaskDecision {
        Objects.requireNonNull(route, "route 不能为空");
        Objects.requireNonNull(taskKind, "taskKind 不能为空");
        Objects.requireNonNull(complexity, "complexity 不能为空");
        requiredCapabilities = Set.copyOf(Objects.requireNonNull(
                requiredCapabilities, "requiredCapabilities 不能为空"));
        if (requiredCapabilities.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("requiredCapabilities 不能包含 null");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason 不能为空");
        }
        switch (route) {
            case CHAT -> {
                if (taskKind != TaskKind.CHAT || !requiredCapabilities.isEmpty()) {
                    throw new IllegalArgumentException(
                            "CHAT 路线必须使用 CHAT 类型且不声明执行能力");
                }
            }
            case KNOWLEDGE -> {
                if (taskKind != TaskKind.PROJECT_QUERY
                        || !requiredCapabilities.equals(Set.of(RequiredCapability.CODE_READ))) {
                    throw new IllegalArgumentException(
                            "KNOWLEDGE 路线必须使用 PROJECT_QUERY 类型且仅声明 CODE_READ 能力");
                }
            }
            case AGENT -> {
                if (taskKind == TaskKind.CHAT
                        || taskKind == TaskKind.PROJECT_QUERY
                        || requiredCapabilities.isEmpty()) {
                    throw new IllegalArgumentException(
                            "AGENT 路线必须声明执行类型和非空执行能力");
                }
            }
        }
    }
}
