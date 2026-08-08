package com.agent.core.tool;

import com.agent.core.engine.NodeExecutionContext;

import java.util.Map;
import java.util.Objects;

/** 将受治理工具调用接入现有 Harness 生命周期的适配器。 */
public final class HarnessToolExecutor {

    private final ToolRegistry registry;

    /** 创建绑定指定注册表的 Harness 适配器。 */
    public HarnessToolExecutor(ToolRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry 不能为空");
    }

    /** 在当前节点上下文中执行工具并发布生命周期事件。 */
    public ToolResult execute(ToolCall call, ToolInvocationContext toolContext) throws Exception {
        Objects.requireNonNull(call, "call 不能为空");
        Objects.requireNonNull(toolContext, "toolContext 不能为空");
        NodeExecutionContext nodeContext = NodeExecutionContext.current()
                .orElseThrow(() -> new IllegalStateException("当前没有节点执行上下文"));
        if (!nodeContext.runId().equals(toolContext.runId())
                || !nodeContext.nodeName().equals(toolContext.nodeName())) {
            throw new IllegalArgumentException("工具上下文必须匹配当前节点");
        }
        String riskLevel = registry.find(call.name())
                .map(definition -> definition.riskLevel().name())
                .orElse("UNKNOWN");
        Map<String, String> metadata = Map.of(
                "toolName", call.name(),
                "callId", call.callId(),
                "riskLevel", riskLevel);
        try {
            return NodeExecutionContext.callTool(call.name(), metadata, () -> {
                ToolResult result = registry.execute(call, toolContext);
                if (result.status() != ToolResultStatus.SUCCEEDED) {
                    throw new ToolResultFailure(result);
                }
                return result;
            });
        } catch (ToolResultFailure failure) {
            return failure.result();
        }
    }

    /** 仅用于在 Harness FAILURE 事件完成后还原原始工具结果。 */
    private static final class ToolResultFailure extends Exception {
        private final ToolResult result;

        private ToolResultFailure(ToolResult result) {
            super(result.errorStack());
            this.result = result;
        }

        private ToolResult result() {
            return result;
        }
    }
}
