package com.agent.rag.pipeline;

import com.agent.core.llm.ChatMessage;
import com.agent.core.llm.ModelRequest;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.RoutedCompletion;
import com.agent.core.llm.TaskType;
import com.agent.core.prompt.RenderedPrompt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 使用快速分类路由生成严格 JSON 查询数组。 */
public final class ModelQueryRewriter implements QueryRewriter {

    private final ModelRouter modelRouter;
    private final ObjectMapper objectMapper;

    /** 创建模型查询改写器。 */
    public ModelQueryRewriter(ModelRouter modelRouter, ObjectMapper objectMapper) {
        this.modelRouter = Objects.requireNonNull(modelRouter, "modelRouter 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /** 生成不超过 limit 条的额外查询。 */
    @Override
    public List<String> rewrite(String query, int limit) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit 必须大于 0");
        }
        RenderedPrompt prompt = RagPromptTemplates.catalog().render(
                "rag.rewrite",
                "1",
                Map.of("query", query, "limit", Integer.toString(limit)));
        RoutedCompletion completion = complete(prompt);
        try {
            ChatMessage message = completion.response().choices().getFirst().message();
            if (!(message.content() instanceof ChatMessage.TextContent textContent)) {
                throw new IllegalArgumentException(
                        "查询改写模型响应 content 必须是 TextContent");
            }
            return parse(textContent.text(), limit);
        } catch (Exception exception) {
            throw invalidResponse(completion, exception);
        }
    }

    private RoutedCompletion complete(RenderedPrompt prompt) {
        try {
            return modelRouter.complete(
                    TaskType.QUICK_CLASSIFICATION,
                    new ModelRequest(
                            List.of(
                                    ChatMessage.system(prompt.staticSection()),
                                    ChatMessage.user(prompt.dynamicSection())),
                            List.of(),
                            null,
                            0.0));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("查询改写模型调用失败", exception);
        }
    }

    private List<String> parse(String json, int limit) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("查询改写结果必须是 JSON 数组");
        }
        if (root.size() > limit) {
            throw new IllegalArgumentException(
                    "查询改写结果超过 limit: " + root.size() + " > " + limit);
        }
        List<String> queries = new ArrayList<>(root.size());
        for (JsonNode item : root) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException(
                        "查询改写数组元素必须是非空字符串");
            }
            queries.add(item.textValue().trim());
        }
        return List.copyOf(queries);
    }

    private IllegalStateException invalidResponse(
            RoutedCompletion completion, Exception cause) {
        return new IllegalStateException(
                "查询改写模型响应无效: endpoint=" + completion.endpointName()
                        + ", model=" + completion.model(),
                cause);
    }
}
