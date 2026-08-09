package com.agent.core.multiagent;

import java.util.LinkedHashSet;
import java.util.Set;

/** Agent 图、状态权限和允许移交目标的不可变描述。 */
public record AgentDescriptor(
        String agentId,
        String graphId,
        Set<String> readableStateKeys,
        Set<String> ownedStateKeys,
        Set<String> handoffTargets) {

    public AgentDescriptor {
        requireText(agentId, "agentId");
        requireText(graphId, "graphId");
        readableStateKeys = freezeKeys(readableStateKeys, "readableStateKeys");
        ownedStateKeys = freezeKeys(ownedStateKeys, "ownedStateKeys");
        handoffTargets = freezeKeys(handoffTargets, "handoffTargets");

        Set<String> overlap = new LinkedHashSet<>(readableStateKeys);
        overlap.retainAll(ownedStateKeys);
        if (!overlap.isEmpty()) {
            throw new AgentDescriptorException("状态键不能同时可读和拥有: " + overlap);
        }
    }

    private static Set<String> freezeKeys(Set<String> keys, String field) {
        if (keys == null) {
            throw new AgentDescriptorException(field + " 不能为空");
        }
        LinkedHashSet<String> checked = new LinkedHashSet<>();
        for (String key : keys) {
            requireText(key, field);
            checked.add(key);
        }
        return Set.copyOf(checked);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AgentDescriptorException(field + " 不能为空");
        }
    }
}
