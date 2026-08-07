package com.agent.core.intent;

import com.agent.core.llm.ChatMessage;
import com.agent.core.llm.ModelRequest;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.RoutedCompletion;
import com.agent.core.llm.TaskType;

import java.util.List;
import java.util.Objects;

/** 使用现有 `ModelRouter` 完成语义意图分类。 */
public final class ModelRouterIntentModel implements IntentModel {

    private final ModelRouter modelRouter;

    public ModelRouterIntentModel(ModelRouter modelRouter) {
        this.modelRouter = Objects.requireNonNull(modelRouter, "modelRouter 不能为空");
    }

    /** 使用 QUICK_CLASSIFICATION 路由并要求纯文本响应。 */
    @Override
    public String classify(List<ChatMessage> messages) {
        RoutedCompletion completion = modelRouter.complete(
                TaskType.QUICK_CLASSIFICATION,
                new ModelRequest(messages, List.of(), null, 0.0));
        ChatMessage message = completion.response().choices().getFirst().message();
        if (!(message.content() instanceof ChatMessage.TextContent textContent)) {
            throw new IllegalStateException("任务路由模型响应 content 必须是 TextContent");
        }
        return textContent.text();
    }
}
