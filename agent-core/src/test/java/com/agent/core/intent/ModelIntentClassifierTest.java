package com.agent.core.intent;

import com.agent.core.prompt.PromptCatalog;
import com.agent.core.prompt.PromptTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelIntentClassifierTest {

    @Test
    void routesExplicitMixedCodeChangeWithoutSemanticModelCall() {
        ModelIntentClassifier classifier = classifier(messages -> {
            throw new AssertionError("明确代码动作不应调用语义模型");
        });

        TaskDecision decision = classifier.classify(
                List.of(), "解释这个类并修改 README.md");

        assertThat(decision.route()).isEqualTo(TaskRoute.AGENT);
        assertThat(decision.taskKind()).isEqualTo(TaskKind.MIXED);
        assertThat(decision.complexity()).isEqualTo(TaskComplexity.COMPLEX);
        assertThat(decision.requiredCapabilities()).containsExactlyInAnyOrder(
                RequiredCapability.CODE_READ,
                RequiredCapability.CODE_WRITE);
    }

    @Test
    void routesDirectQuestionWithoutSemanticModelCall() {
        ModelIntentClassifier classifier = classifier(messages -> {
            throw new AssertionError("明确问答不应调用语义模型");
        });

        TaskDecision decision = classifier.classify(List.of(), "你是什么模型");

        assertThat(decision).isEqualTo(new TaskDecision(
                TaskRoute.CHAT,
                TaskKind.CHAT,
                TaskComplexity.SIMPLE,
                Set.of(),
                "检测到明确自然语言问答"));
    }

    @Test
    void parsesExactSemanticDecisionJson() {
        ModelIntentClassifier classifier = classifier(messages -> """
                {"route":"CHAT","taskKind":"CHAT","complexity":"SIMPLE",
                 "requiredCapabilities":[],"reason":"无需工具"}
                """);

        assertThat(classifier.classify(List.of(), "按天气规划"))
                .isEqualTo(new TaskDecision(
                        TaskRoute.CHAT,
                        TaskKind.CHAT,
                        TaskComplexity.SIMPLE,
                        Set.of(),
                        "无需工具"));
    }

    @Test
    void includesHistoryAndRenderedRoutePromptInSemanticRequest() {
        IntentModel model = messages -> {
            assertThat(messages)
                    .extracting(message -> message.role().jsonValue())
                    .containsExactly("system", "user", "assistant", "user");
            assertThat(messages.getLast().content())
                    .isEqualTo(new com.agent.core.llm.ChatMessage.TextContent("任务：接着说"));
            return """
                    {"route":"CHAT","taskKind":"CHAT","complexity":"STANDARD",
                     "requiredCapabilities":[],"reason":"延续对话"}
                    """;
        };
        ModelIntentClassifier classifier = classifier(model);

        TaskDecision decision = classifier.classify(List.of(
                com.agent.core.llm.ChatMessage.user("上一个问题"),
                com.agent.core.llm.ChatMessage.assistant("上一个回答")), "接着说");

        assertThat(decision.reason()).isEqualTo("延续对话");
    }

    @Test
    void fallsBackToSideEffectFreeChatWhenSemanticJsonIsInvalid() {
        ModelIntentClassifier classifier = classifier(messages -> "agent");

        TaskDecision decision = classifier.classify(List.of(), "接着说");

        assertThat(decision.route()).isEqualTo(TaskRoute.CHAT);
        assertThat(decision.taskKind()).isEqualTo(TaskKind.CHAT);
        assertThat(decision.requiredCapabilities()).isEmpty();
        assertThat(decision.reason()).contains("结构不合法");
    }

    @Test
    void rejectsContradictoryTypedDecision() {
        assertThatThrownBy(() -> new TaskDecision(
                TaskRoute.CHAT,
                TaskKind.CODE_CHANGE,
                TaskComplexity.SIMPLE,
                Set.of(RequiredCapability.CODE_WRITE),
                "冲突"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CHAT");
    }

    private ModelIntentClassifier classifier(IntentModel model) {
        PromptCatalog catalog = new PromptCatalog(List.of(new PromptTemplate(
                "planner.route",
                "1",
                "只输出严格 JSON。",
                "任务：{{task}}",
                Set.of("task"))));
        return new ModelIntentClassifier(model, new ObjectMapper(), catalog);
    }
}
