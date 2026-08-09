package com.agent.eval;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 任务到能力的显式评测编排。 */
public record EvaluationSuite(
        String id,
        BenchmarkTaskSet taskSet,
        Map<String, String> taskCapabilities,
        List<EvaluationCapability> capabilities,
        EvaluationGatePolicy policy) {

    public EvaluationSuite {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id 不能为空");
        }
        taskSet = Objects.requireNonNull(taskSet, "taskSet 不能为空");
        taskCapabilities = Map.copyOf(Objects.requireNonNull(
                taskCapabilities, "taskCapabilities 不能为空"));
        capabilities = List.copyOf(Objects.requireNonNull(
                capabilities, "capabilities 不能为空"));
        policy = Objects.requireNonNull(policy, "policy 不能为空");
        Map<String, EvaluationCapability> byId = new HashMap<>();
        for (EvaluationCapability capability : capabilities) {
            Objects.requireNonNull(capability, "capabilities 不能包含 null");
            if (byId.putIfAbsent(capability.id(), capability) != null) {
                throw new IllegalArgumentException("能力 ID 必须唯一: " + capability.id());
            }
        }
        Set<String> taskIds = taskSet.tasks().stream()
                .map(BenchmarkTask::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!taskCapabilities.keySet().equals(taskIds)) {
            throw new IllegalArgumentException("taskCapabilities 必须精确覆盖任务 ID");
        }
        if (taskCapabilities.values().stream().anyMatch(value -> !byId.containsKey(value))) {
            throw new IllegalArgumentException("taskCapabilities 包含未知能力 ID");
        }
        if (byId.isEmpty()) {
            throw new IllegalArgumentException("capabilities 不能为空");
        }
        Set<String> mappedCapabilityIds = Set.copyOf(taskCapabilities.values());
        if (byId.keySet().stream().anyMatch(capabilityId -> !mappedCapabilityIds.contains(capabilityId))) {
            throw new IllegalArgumentException("能力必须至少绑定一个任务");
        }
        capabilities = capabilities.stream()
                .sorted(java.util.Comparator.comparing(EvaluationCapability::id))
                .toList();
    }

    /** 返回任务对应的精确能力。 */
    public EvaluationCapability capabilityFor(String taskId) {
        Objects.requireNonNull(taskId, "taskId 不能为空");
        String capabilityId = taskCapabilities.get(taskId);
        if (capabilityId == null) {
            throw new IllegalArgumentException("未知任务 ID: " + taskId);
        }
        return capabilities.stream()
                .filter(capability -> capability.id().equals(capabilityId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("能力映射不完整: " + capabilityId));
    }
}
