package com.agent.web.mcp.runtime;

import com.agent.web.mcp.installation.McpSourceSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 仅在启动时传递、从不持久化的 MCP 运行环境变量端口。 */
@FunctionalInterface
public interface McpRuntimeSecretProvider {

    Map<String, String> resolve(McpSourceSnapshot snapshot, Map<String, String> submittedEnvironment);

    /** 默认实现仅接受快照明确声明的环境变量名。 */
    static McpRuntimeSecretProvider declaredNamesOnly() {
        return (snapshot, submittedEnvironment) -> {
            Objects.requireNonNull(snapshot, "snapshot 不能为空");
            Map<String, String> values = submittedEnvironment == null ? Map.of() : submittedEnvironment;
            Map<String, String> resolved = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                String name = entry.getKey();
                if (!snapshot.environmentVariableNames().contains(name)) {
                    throw new IllegalArgumentException("环境变量未在 MCP 快照中声明: " + name);
                }
                if (entry.getValue() == null) {
                    throw new IllegalArgumentException("MCP 环境变量值不能为空: " + name);
                }
                resolved.put(name, entry.getValue());
            }
            return Map.copyOf(resolved);
        };
    }
}
