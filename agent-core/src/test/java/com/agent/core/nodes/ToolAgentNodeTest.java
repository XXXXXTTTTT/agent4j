package com.agent.core.nodes;

import com.agent.core.engine.AgentState;
import com.agent.core.llm.ChatMessage;
import com.agent.core.llm.LlmClient;
import com.agent.core.llm.ModelRequest;
import com.agent.core.llm.RoutedCompletion;
import com.agent.core.skill.SkillCatalog;
import com.agent.core.skill.SkillDefinition;
import com.agent.core.tool.DefaultToolRegistry;
import com.agent.core.tool.ToolDefinition;
import com.agent.core.tool.ToolRiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ToolAgentNodeTest {

    @Test
    void doesNotForceStrictToolSchemasForGatewayRequests() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<ModelRequest> captured = new AtomicReference<>();
        try (DefaultToolRegistry registry = new DefaultToolRegistry()) {
            registry.register(new ToolDefinition(
                    "artifact.create",
                    "生成工件",
                    mapper.readTree("{\"type\":\"object\",\"properties\":{\"prompt\":{\"type\":\"string\"}},\"required\":[\"prompt\"],\"additionalProperties\":false}"),
                    Set.of(com.agent.core.intent.RequiredCapability.TOOL),
                    ToolRiskLevel.LOW,
                    Duration.ofSeconds(2),
                    (call, context) -> mapper.createObjectNode().put("ok", true)));
            ToolAgentNode node = new ToolAgentNode(request -> {
                captured.set(request);
                return completion(ChatMessage.assistant("已完成"), "tool-model");
            }, registry, mapper, null, 1);

            node.execute(AgentState.empty().withVariable(PlannerNode.TASK_KEY, "生成工件"));

            assertThat(captured.get().tools()).singleElement()
                    .extracting(tool -> tool.function().strict())
                    .isNull();
        }
    }

    @Test
    void normalizesToolNamesForGatewayAndKeepsCollisionsUnique() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<ModelRequest> captured = new AtomicReference<>();
        try (DefaultToolRegistry registry = new DefaultToolRegistry()) {
            for (String name : List.of("artifact.create", "artifact_create", "code.apply-diff")) {
                registry.register(new ToolDefinition(name, "测试说明",
                        mapper.readTree("{\"type\":\"object\"}"),
                        Set.of(com.agent.core.intent.RequiredCapability.TOOL), ToolRiskLevel.LOW,
                        Duration.ofSeconds(2), (call, context) -> mapper.createObjectNode().put("ok", true)));
            }
            ToolAgentNode node = new ToolAgentNode(request -> {
                captured.set(request);
                return completion(ChatMessage.assistant("已完成"), "tool-model");
            }, registry, mapper, null, 1);

            node.execute(AgentState.empty().withVariable(PlannerNode.TASK_KEY, "调用工具"));

            assertThat(captured.get().tools()).extracting(tool -> tool.function().name())
                    .containsExactly("artifact_create", "artifact_create_2", "code_apply_diff")
                    .allMatch(name -> name.matches("[A-Za-z0-9_-]+"));
            String systemPrompt = ((ChatMessage.TextContent) captured.get().messages().getFirst().content()).text();
            assertThat(systemPrompt)
                    .contains("artifact_create", "artifact_create_2", "code_apply_diff")
                    .doesNotContain("artifact.create", "code.apply-diff");
        }
    }

    @Test
    void executesOriginalToolWhenModelReturnsNormalizedName() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger calls = new AtomicInteger();
        try (DefaultToolRegistry registry = new DefaultToolRegistry()) {
            registry.register(new ToolDefinition("artifact.create", "生成工件",
                    mapper.readTree("{\"type\":\"object\"}"),
                    Set.of(com.agent.core.intent.RequiredCapability.TOOL), ToolRiskLevel.LOW,
                    Duration.ofSeconds(2), (call, context) -> {
                        calls.incrementAndGet();
                        return mapper.createObjectNode().put("ok", true);
                    }));
            ToolAgentNode node = new ToolAgentNode(request -> {
                if (calls.get() == 0) {
                    ChatMessage.ToolCall call = new ChatMessage.ToolCall("call-1", "function",
                            new ChatMessage.FunctionCall("artifact_create", "{}"));
                    return completion(ChatMessage.assistantToolCalls(List.of(call)), "tool-model");
                }
                return completion(ChatMessage.assistant("完成"), "tool-model");
            }, registry, mapper, null, 2);

            AgentState result = node.execute(AgentState.empty().withVariable(PlannerNode.TASK_KEY, "调用工具"));

            assertThat(calls).hasValue(1);
            assertThat(result.variables()).doesNotContainKey(ToolAgentNode.ERROR_KEY);
        }
    }

    @Test
    void executesToolCallThenPersistsFinalResponse() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger calls = new AtomicInteger();
        try (DefaultToolRegistry registry = new DefaultToolRegistry()) {
            registry.register(new ToolDefinition(
                    "artifact.create",
                    "生成工件",
                    mapper.readTree("{\"type\":\"object\",\"properties\":{\"prompt\":{\"type\":\"string\"}},\"required\":[\"prompt\"],\"additionalProperties\":false}"),
                    Set.of(com.agent.core.intent.RequiredCapability.TOOL),
                    ToolRiskLevel.LOW,
                    Duration.ofSeconds(2),
                    (call, context) -> mapper.createObjectNode()
                            .put("markdown", "![生成结果](data:image/png;base64,AA==)")));
            SkillCatalog skills = new SkillCatalog(List.of(new SkillDefinition(
                    "artifact-generation", "1.0.0", "工件生成", List.of("生成工件"),
                    List.of("artifact.create"), "先调用工件工具")), registry, mapper);
            ToolAgentNode node = new ToolAgentNode(request -> {
                if (calls.getAndIncrement() == 0) {
                    ChatMessage.ToolCall call = new ChatMessage.ToolCall(
                            "image-call-1", "function",
                            new ChatMessage.FunctionCall(
                                    "artifact_create", "{\"prompt\":\"蓝色方块\"}"));
                    return completion(ChatMessage.assistantToolCalls(List.of(call)), "tool-model");
                }
                return completion(ChatMessage.assistant("图片已生成。\n\n![生成结果](data:image/png;base64,AA==)"), "tool-model");
            }, registry, mapper, skills, 3);

            AgentState result = node.execute(AgentState.empty()
                    .withVariable(PlannerNode.TASK_KEY, "生成工件")
                    .withVariable(PlannerNode.USER_ID_KEY, "local"));

            assertThat(calls).hasValue(2);
            assertThat(result.variables())
                    .containsEntry(PlannerNode.FINAL_RESPONSE_KEY, "图片已生成。\n\n![生成结果](data:image/png;base64,AA==)")
                    .containsKey(ToolAgentNode.RESULT_KEY)
                    .doesNotContainKey(ToolAgentNode.ERROR_KEY);
        }
    }

    @Test
    void invokesSingleImageSkillWithoutToolPlanningModel() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger toolCalls = new AtomicInteger();
        try (DefaultToolRegistry registry = new DefaultToolRegistry()) {
            registry.register(new ToolDefinition(
                    "image.generate",
                    "生成图片",
                    mapper.readTree("{\"type\":\"object\",\"properties\":{\"prompt\":{\"type\":\"string\"}},\"required\":[\"prompt\"],\"additionalProperties\":false}"),
                    Set.of(com.agent.core.intent.RequiredCapability.TOOL),
                    ToolRiskLevel.LOW,
                    Duration.ofSeconds(2),
                    (call, context) -> {
                        toolCalls.incrementAndGet();
                        assertThat(call.arguments().path("prompt").asText())
                                .isEqualTo("请生成一张蓝色方块图片");
                        return mapper.createObjectNode()
                                .put("type", "image")
                                .put("dataUrl", "data:image/png;base64,AA==")
                                .put("revisedPrompt", "蓝色方块")
                                .put("model", "image-model");
                    }));
            SkillCatalog skills = new SkillCatalog(List.of(new SkillDefinition(
                    "image-generation", "1.0.0", "图片生成", List.of("生成一张"),
                    List.of("image.generate"), "直接调用图片工具")), registry, mapper);
            ToolAgentNode node = new ToolAgentNode(request -> {
                throw new AssertionError("单图片 Skill 不应调用工具编排模型");
            }, registry, mapper, skills, 3);

            AgentState result = node.execute(AgentState.empty()
                    .withVariable(PlannerNode.TASK_KEY, "请生成一张蓝色方块图片")
                    .withVariable(PlannerNode.USER_ID_KEY, "local"));

            assertThat(toolCalls).hasValue(1);
            assertThat(result.variables())
                    .containsEntry(PlannerNode.FINAL_RESPONSE_KEY,
                            "图片已生成。\n\n![生成图片](data:image/png;base64,AA==)\n\n模型：`image-model`")
                    .containsEntry(ToolAgentNode.ACTIVE_SKILLS_KEY,
                            "image-generation@1.0.0")
                    .containsKey(ToolAgentNode.RESULT_KEY)
                    .containsKey(ToolAgentNode.SKILL_FINGERPRINT_KEY)
                    .doesNotContainKey(ToolAgentNode.ERROR_KEY);
        }
    }

    @Test
    void returnsReadableFailureWhenImageGatewayFails() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (DefaultToolRegistry registry = new DefaultToolRegistry()) {
            registry.register(new ToolDefinition(
                    "image.generate",
                    "生成图片",
                    mapper.readTree("{\"type\":\"object\",\"properties\":{\"prompt\":{\"type\":\"string\"}},\"required\":[\"prompt\"],\"additionalProperties\":false}"),
                    Set.of(com.agent.core.intent.RequiredCapability.TOOL),
                    ToolRiskLevel.LOW,
                    Duration.ofSeconds(2),
                    (call, context) -> {
                        throw new IllegalStateException("upstream unavailable");
                    }));
            SkillCatalog skills = new SkillCatalog(List.of(new SkillDefinition(
                    "image-generation", "1.0.0", "图片生成", List.of("生成一张"),
                    List.of("image.generate"), "直接调用图片工具")), registry, mapper);
            ToolAgentNode node = new ToolAgentNode(request -> {
                throw new AssertionError("图片失败不应回退到工具编排模型");
            }, registry, mapper, skills, 3);

            AgentState result = node.execute(AgentState.empty()
                    .withVariable(PlannerNode.TASK_KEY, "请生成一张蓝色方块图片")
                    .withVariable(PlannerNode.USER_ID_KEY, "local"));

            assertThat(result.variables())
                    .containsEntry(PlannerNode.FINAL_RESPONSE_KEY,
                            "图片生成服务已被真实调用，但上游返回失败。完整 HTTP 错误已写入工具审计，请稍后重试。")
                    .containsKey(ToolAgentNode.ERROR_KEY);
        }
    }

    private RoutedCompletion completion(ChatMessage message, String model) {
        return new RoutedCompletion(
                "tool-endpoint",
                model,
                new LlmClient.ChatCompletionResponse(
                        "response-1",
                        "chat.completion",
                        1L,
                        model,
                        List.of(new LlmClient.Choice(0, message, "stop")),
                        new LlmClient.Usage(1, 1, 2)));
    }
}
