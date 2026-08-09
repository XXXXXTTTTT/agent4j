package com.agent.core.multiagent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 按精确 Agent 标识发布的只读目录。 */
public final class AgentCatalog {

    private final List<AgentDescriptor> descriptors;
    private final Map<String, AgentDescriptor> byId;

    public AgentCatalog(List<AgentDescriptor> descriptors) {
        if (descriptors == null || descriptors.isEmpty()) {
            throw new AgentDescriptorException("descriptors 不能为空列表");
        }
        Map<String, AgentDescriptor> checked = new LinkedHashMap<>();
        for (AgentDescriptor descriptor : descriptors) {
            if (descriptor == null) {
                throw new AgentDescriptorException("descriptor 不能为空");
            }
            if (checked.putIfAbsent(descriptor.agentId(), descriptor) != null) {
                throw new AgentDescriptorException("Agent 重复注册: " + descriptor.agentId());
            }
        }
        for (AgentDescriptor descriptor : checked.values()) {
            for (String target : descriptor.handoffTargets()) {
                if (descriptor.agentId().equals(target)) {
                    throw new AgentDescriptorException("Agent 不得移交给自身: " + target);
                }
                if (!checked.containsKey(target)) {
                    throw new AgentDescriptorException("Handoff 目标未注册: " + target);
                }
            }
        }
        this.descriptors = List.copyOf(new ArrayList<>(checked.values()));
        this.byId = Map.copyOf(checked);
    }

    public List<AgentDescriptor> list() {
        return descriptors;
    }

    public AgentDescriptor require(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId 不能为空");
        }
        AgentDescriptor descriptor = byId.get(agentId);
        if (descriptor == null) {
            throw new AgentNotFoundException(agentId);
        }
        return descriptor;
    }
}
