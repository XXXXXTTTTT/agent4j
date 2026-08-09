package com.agent.core.profile;

import com.agent.core.engine.ExecutionBudget;
import com.agent.core.llm.TaskType;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** 由应用构造器声明的不可变 Agent 能力档案。 */
public record AgentProfile(
        String profileId,
        String graphId,
        String displayName,
        String description,
        Set<TaskType> taskTypes,
        Set<String> capabilities,
        ExecutionBudget executionBudget) {

    /** 校验并冻结 Profile 元数据，不对标识和能力标签做格式推断。 */
    public AgentProfile {
        requireText(profileId, "profileId");
        requireText(graphId, "graphId");
        requireText(displayName, "displayName");
        requireText(description, "description");
        Objects.requireNonNull(taskTypes, "taskTypes 不能为空");
        Objects.requireNonNull(capabilities, "capabilities 不能为空");
        Objects.requireNonNull(executionBudget, "executionBudget 不能为空");

        taskTypes = Set.copyOf(taskTypes);
        LinkedHashSet<String> checkedCapabilities = new LinkedHashSet<>();
        for (String capability : capabilities) {
            requireText(capability, "capabilities");
            checkedCapabilities.add(capability);
        }
        capabilities = Set.copyOf(checkedCapabilities);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}
