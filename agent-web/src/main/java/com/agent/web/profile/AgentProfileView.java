package com.agent.web.profile;

import com.agent.core.engine.ExecutionBudget;
import com.agent.core.llm.TaskType;
import com.agent.core.profile.AgentProfile;

import java.util.Objects;
import java.util.Set;

/** Agent Profile 声明的 HTTP 只读视图。 */
public record AgentProfileView(
        String profileId,
        String graphId,
        String displayName,
        String description,
        Set<TaskType> taskTypes,
        Set<String> capabilities,
        ExecutionBudget executionBudget) {

    /** 从核心 Profile 创建 HTTP 视图。 */
    public static AgentProfileView from(AgentProfile profile) {
        Objects.requireNonNull(profile, "profile 不能为空");
        return new AgentProfileView(
                profile.profileId(),
                profile.graphId(),
                profile.displayName(),
                profile.description(),
                profile.taskTypes(),
                profile.capabilities(),
                profile.executionBudget());
    }
}
