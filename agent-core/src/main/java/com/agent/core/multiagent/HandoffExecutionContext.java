package com.agent.core.multiagent;

import java.util.ArrayList;
import java.util.List;

/** Handoff 深度、剩余次数和访问链的不可变执行边界。 */
public record HandoffExecutionContext(
        int currentDepth,
        int maxDepth,
        int remainingHandoffs,
        List<String> visitedAgents) {

    public HandoffExecutionContext {
        if (currentDepth < 0) {
            throw new IllegalArgumentException("currentDepth 不能小于 0");
        }
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("maxDepth 必须大于 0");
        }
        if (remainingHandoffs < 0) {
            throw new IllegalArgumentException("remainingHandoffs 不能小于 0");
        }
        if (visitedAgents == null || visitedAgents.isEmpty()) {
            throw new IllegalArgumentException("visitedAgents 不能为空列表");
        }
        List<String> checked = new ArrayList<>();
        for (String agentId : visitedAgents) {
            requireAgentId(agentId);
            if (checked.contains(agentId)) {
                throw new IllegalArgumentException("visitedAgents 不得包含重复 Agent: " + agentId);
            }
            checked.add(agentId);
        }
        if (checked.size() != currentDepth + 1) {
            throw new IllegalArgumentException("visitedAgents 数量必须等于 currentDepth + 1");
        }
        visitedAgents = List.copyOf(checked);
    }

    public static HandoffExecutionContext root(
            String rootAgent,
            int maxDepth,
            int maxHandoffs) {
        requireAgentId(rootAgent);
        return new HandoffExecutionContext(0, maxDepth, maxHandoffs, List.of(rootAgent));
    }

    public HandoffExecutionContext descend(String toAgent) {
        requireAgentId(toAgent);
        String fromAgent = visitedAgents.getLast();
        if (currentDepth >= maxDepth) {
            throw new AgentHandoffDeniedException(
                    fromAgent, toAgent, "Handoff 深度已耗尽");
        }
        if (remainingHandoffs == 0) {
            throw new AgentHandoffDeniedException(
                    fromAgent, toAgent, "Handoff 次数已耗尽");
        }
        if (visitedAgents.contains(toAgent)) {
            throw new AgentHandoffDeniedException(
                    fromAgent, toAgent, "Handoff 访问环被拒绝: " + toAgent);
        }
        List<String> descended = new ArrayList<>(visitedAgents);
        descended.add(toAgent);
        return new HandoffExecutionContext(
                currentDepth + 1,
                maxDepth,
                remainingHandoffs - 1,
                descended);
    }

    private static void requireAgentId(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId 不能为空");
        }
    }
}
