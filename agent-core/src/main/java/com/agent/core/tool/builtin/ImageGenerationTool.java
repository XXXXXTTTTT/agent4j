package com.agent.core.tool.builtin;

import com.agent.core.intent.RequiredCapability;
import com.agent.core.llm.ImageGenerationClient;
import com.agent.core.tool.ToolDefinition;
import com.agent.core.tool.ToolRiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.Set;

/** 将独立 Images API 暴露为受 ToolRegistry 治理的图片工件工具。 */
public final class ImageGenerationTool {

    public static final String NAME = "image.generate";

    private ImageGenerationTool() {
    }

    public static ToolDefinition definition(
            ImageGenerationClient client,
            ObjectMapper objectMapper,
            Duration timeout) {
        ObjectNode schema = objectMapper.createObjectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        schema.putObject("properties")
                .putObject("prompt")
                .put("type", "string");
        schema.putArray("required").add("prompt");
        return new ToolDefinition(
                NAME,
                "根据文字描述生成图片并返回可渲染图片工件",
                schema,
                Set.of(RequiredCapability.TOOL),
                ToolRiskLevel.LOW,
                timeout,
                (call, context) -> {
                    String prompt = call.arguments().path("prompt").textValue();
                    if (prompt == null || prompt.isBlank()) {
                        throw new IllegalArgumentException("image.generate prompt 不能为空");
                    }
                    ImageGenerationClient.GeneratedImage image = client.generate(prompt);
                    return objectMapper.createObjectNode()
                            .put("type", "image")
                            .put("dataUrl", image.dataUrl())
                            .put("revisedPrompt", image.revisedPrompt())
                            .put("model", image.model());
                });
    }
}
