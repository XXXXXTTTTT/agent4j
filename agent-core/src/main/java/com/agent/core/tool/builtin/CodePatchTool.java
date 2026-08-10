package com.agent.core.tool.builtin;

import com.agent.core.intent.RequiredCapability;
import com.agent.core.tool.ToolDefinition;
import com.agent.core.tool.ToolHandler;
import com.agent.core.tool.ToolRiskLevel;
import com.agent.core.tool.ToolCall;
import com.agent.core.tool.ToolInvocationContext;
import com.agent.sandbox.ast.AstService;
import com.agent.sandbox.ast.AstService.AppliedDiff;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/** 通过统一工具治理端口应用工作区 Unified Diff。 */
public final class CodePatchTool {

    /** 注册到 ToolRegistry 的精确名称。 */
    public static final String NAME = "code.apply-diff";

    private CodePatchTool() {
    }

    /** 创建绑定 AstService 的不可变工具定义。 */
    public static ToolDefinition definition(AstService astService, ObjectMapper objectMapper) {
        Objects.requireNonNull(astService, "astService 不能为空");
        Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        ObjectNode schema = objectMapper.createObjectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("unifiedDiff", objectMapper.createObjectNode()
                .put("type", "string")
                .put("minLength", 1));
        schema.set("properties", properties);
        schema.putArray("required").add("unifiedDiff");

        ToolHandler handler = (call, context) -> apply(
                astService, objectMapper, call, context);
        return new ToolDefinition(
                NAME,
                "在绑定工作区内应用 Unified Diff 并返回实际更新文件",
                schema,
                Set.of(RequiredCapability.CODE_WRITE),
                ToolRiskLevel.LOW,
                Duration.ofMinutes(2),
                handler);
    }

    private static JsonNode apply(
            AstService astService,
            ObjectMapper objectMapper,
            ToolCall call,
            ToolInvocationContext context) {
        String unifiedDiff = call.arguments().path("unifiedDiff").textValue();
        if (unifiedDiff == null || unifiedDiff.isBlank()) {
            throw new IllegalArgumentException("unifiedDiff 不能为空");
        }
        AppliedDiff applied = astService.applyDiffWithEvidence(
                context.workspaceRoot(), unifiedDiff);
        ArrayNode files = objectMapper.createArrayNode();
        for (Path updatedFile : applied.updatedFiles()) {
            Path absolute = updatedFile.toAbsolutePath().normalize();
            if (!absolute.startsWith(context.workspaceRoot())) {
                throw new IllegalStateException("工具更新文件超出 workspaceRoot");
            }
            files.add(context.workspaceRoot().relativize(absolute)
                    .toString().replace('\\', '/'));
        }
        ObjectNode output = objectMapper.createObjectNode();
        output.set("updatedFiles", files);
        output.put("unifiedDiff", applied.unifiedDiff());
        return output;
    }
}
