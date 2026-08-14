package com.agent.core.orchestration;

import java.util.EnumMap;
import java.util.Map;

/** 校验编排合同并解析每个角色的模型组。 */
public final class OrchestrationRequestValidator {
    private OrchestrationRequestValidator() {
    }

    public static Map<AgentRole, String> validate(
            OrchestrationRequest request,
            String primaryModelGroupId) {
        if (request == null) {
            throw new IllegalArgumentException("编排请求不能为空");
        }
        String primary = requireGroup(primaryModelGroupId, "主模型组");
        EnumMap<AgentRole, String> resolved = new EnumMap<>(AgentRole.class);
        resolved.putAll(Map.of(
                AgentRole.COORDINATOR, primary,
                AgentRole.RESEARCHER, primary,
                AgentRole.IMPLEMENTER, primary,
                AgentRole.VERIFIER, primary));
        for (Map.Entry<AgentRole, String> entry : request.roleModelGroups().entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalArgumentException("角色键不能为空");
            }
            resolved.put(entry.getKey(), requireGroup(entry.getValue(), entry.getKey().name()));
        }
        return Map.copyOf(resolved);
    }

    private static String requireGroup(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "模型组不能为空");
        }
        return value.trim();
    }
}
