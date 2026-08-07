package com.agent.rag.pipeline;

import com.agent.core.llm.ChatMessage;
import com.agent.core.llm.ModelRequest;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.RoutedCompletion;
import com.agent.core.llm.TaskType;
import com.agent.core.prompt.RenderedPrompt;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 使用快速分类路由生成仅供向量召回的 HyDE 正文。 */
public final class ModelHypotheticalDocumentGenerator
        implements HypotheticalDocumentGenerator {

    private final ModelRouter modelRouter;

    /** 创建模型 HyDE 生成器。 */
    public ModelHypotheticalDocumentGenerator(
            ModelRouter modelRouter, ObjectMapper objectMapper) {
        this.modelRouter = Objects.requireNonNull(modelRouter, "modelRouter 不能为空");
        Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /** 返回非空纯文本假设文档。 */
    @Override
    public String generate(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        RenderedPrompt prompt = RagPromptTemplates.catalog().render(
                "rag.hyde", "1", Map.of("query", query));
        RoutedCompletion completion = complete(prompt);
        try {
            ChatMessage message = completion.response().choices().getFirst().message();
            if (!(message.content() instanceof ChatMessage.TextContent textContent)) {
                throw new IllegalArgumentException(
                        "HyDE 模型响应 content 必须是 TextContent");
            }
            if (textContent.text().isBlank()) {
                throw new IllegalArgumentException("HyDE 模型响应不能为空");
            }
            return textContent.text();
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "HyDE 模型响应无效: endpoint=" + completion.endpointName()
                            + ", model=" + completion.model(),
                    exception);
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
            throw new IllegalStateException("HyDE 模型调用失败", exception);
        }
    }
}
