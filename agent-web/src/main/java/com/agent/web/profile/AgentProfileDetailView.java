package com.agent.web.profile;

import com.agent.core.engine.GraphTopology;
import com.agent.core.engine.ExecutionBudget;
import com.agent.core.llm.TaskType;
import com.agent.core.profile.AgentProfileSnapshot;

import java.util.Objects;
import java.util.Set;

/** Agent Profile 与图拓扑的 HTTP 只读详情。 */
public record AgentProfileDetailView(
        String profileId,
        String graphId,
        String displayName,
        String description,
        Set<TaskType> taskTypes,
        Set<String> capabilities,
        ExecutionBudget executionBudget,
        GraphTopology topology) {

    /** 从核心 Profile 快照创建详情视图。 */
    public static AgentProfileDetailView from(AgentProfileSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot 不能为空");
        AgentProfileView profile = AgentProfileView.from(snapshot.profile());
        return new AgentProfileDetailView(
                profile.profileId(),
                profile.graphId(),
                profile.displayName(),
                profile.description(),
                profile.taskTypes(),
                profile.capabilities(),
                profile.executionBudget(),
                snapshot.topology());
    }
}
