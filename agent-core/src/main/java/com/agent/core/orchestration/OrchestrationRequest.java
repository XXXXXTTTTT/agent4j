package com.agent.core.orchestration;

import java.util.Map;
import java.util.Objects;
import java.util.Collections;
import java.util.EnumMap;

/** 用户选择的编排模式与角色模型组覆盖。 */
public record OrchestrationRequest(
        OrchestrationMode mode,
        Map<AgentRole, String> roleModelGroups) {

    public OrchestrationRequest {
        Objects.requireNonNull(mode, "编排模式不能为空");
        if (roleModelGroups == null || roleModelGroups.isEmpty()) {
            roleModelGroups = Map.of();
        } else {
            EnumMap<AgentRole, String> copy = new EnumMap<>(AgentRole.class);
            for (Map.Entry<?, ?> entry : roleModelGroups.entrySet()) {
                Object key = entry.getKey();
                if (!(key instanceof AgentRole role)) {
                    throw new IllegalArgumentException("未知角色键: " + key);
                }
                Object value = entry.getValue();
                if (value != null && !(value instanceof String)) {
                    throw new IllegalArgumentException(role.name() + "模型组必须是字符串");
                }
                copy.put(role, (String) value);
            }
            roleModelGroups = Collections.unmodifiableMap(copy);
        }
    }
}
