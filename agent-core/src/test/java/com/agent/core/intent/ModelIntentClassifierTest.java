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
                RequiredCapability.CODE_WRITE,
                RequiredCapability.TERMINAL);
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
    void routesProjectArchitectureQuestionToReadOnlyKnowledge() {
        ModelIntentClassifier classifier = classifier(messages -> {
            throw new AssertionError("明确项目问答不应调用语义模型");
        });

        TaskDecision decision = classifier.classify(
                List.of(), "请解释当前仓库架构");

        assertThat(decision.route()).isEqualTo(TaskRoute.KNOWLEDGE);
        assertThat(decision.taskKind()).isEqualTo(TaskKind.PROJECT_QUERY);
        assertThat(decision.requiredCapabilities())
                .containsExactly(RequiredCapability.CODE_READ);
    }

    @Test
    void routesProjectImplementationQuestionToReadOnlyKnowledge() {
        ModelIntentClassifier classifier = classifier(messages -> {
            throw new AssertionError("明确项目问答不应调用语义模型");
        });

        TaskDecision decision = classifier.classify(
                List.of(), "这个项目的 PlannerNode 如何路由？");

        assertThat(decision.route()).isEqualTo(TaskRoute.KNOWLEDGE);
        assertThat(decision.taskKind()).isEqualTo(TaskKind.PROJECT_QUERY);
        assertThat(decision.complexity()).isEqualTo(TaskComplexity.STANDARD);
        assertThat(decision.requiredCapabilities())
                .containsExactly(RequiredCapability.CODE_READ);
    }

    @Test
    void keepsGeneralTechnicalQuestionOnChatRoute() {
        ModelIntentClassifier classifier = classifier(messages -> {
            throw new AssertionError("明确普通问答不应调用语义模型");
        });

        TaskDecision decision = classifier.classify(
                List.of(), "什么是 Java 虚拟线程？");

        assertThat(decision.route()).isEqualTo(TaskRoute.CHAT);
        assertThat(decision.taskKind()).isEqualTo(TaskKind.CHAT);
        assertThat(decision.requiredCapabilities()).isEmpty();
    }

    @Test
    void keepsExplicitProjectMutationOnAgentRoute() {
        ModelIntentClassifier classifier = classifier(messages -> {
            throw new AssertionError("明确执行动作不应调用语义模型");
        });

        TaskDecision decision = classifier.classify(
                List.of(), "修改 PlannerNode 并运行测试");

        assertThat(decision.route()).isEqualTo(TaskRoute.AGENT);
        assertThat(decision.taskKind()).isEqualTo(TaskKind.MIXED);
        assertThat(decision.requiredCapabilities()).containsExactlyInAnyOrder(
                RequiredCapability.CODE_READ,
                RequiredCapability.CODE_WRITE,
                RequiredCapability.TERMINAL);
    }

    @Test
    void routesChineseModifyVerbToCodeChangeFastPath() {
        ModelIntentClassifier classifier = classifier(messages -> {
            throw new AssertionError("明确修改动作不应调用语义模型");
        });

        TaskDecision decision = classifier.classify(
                List.of(), "把 value.txt 改成 after 并验证");

        assertThat(decision.route()).isEqualTo(TaskRoute.AGENT);
        assertThat(decision.taskKind()).isEqualTo(TaskKind.CODE_CHANGE);
        assertThat(decision.requiredCapabilities()).containsExactlyInAnyOrder(
                RequiredCapability.CODE_READ,
                RequiredCapability.CODE_WRITE,
                RequiredCapability.TERMINAL);
    }

    @Test
    void routesUserImageRequestToToolOperationFastPath() {
        ModelIntentClassifier classifier = classifier(messages -> {
            throw new AssertionError("明确图片生成动作不应调用语义模型");
        });

        TaskDecision decision = classifier.classify(
                List.of(), "请你帮我生成一张 尸兄 小鹿的图片");

        assertThat(decision.route()).isEqualTo(TaskRoute.AGENT);
        assertThat(decision.taskKind()).isEqualTo(TaskKind.TOOL_OPERATION);
        assertThat(decision.requiredCapabilities())
                .containsExactly(RequiredCapability.TOOL);
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
    void parsesExactSemanticKnowledgeDecisionJson() {
        ModelIntentClassifier classifier = classifier(messages -> """
                {"route":"KNOWLEDGE","taskKind":"PROJECT_QUERY","complexity":"STANDARD",
                 "requiredCapabilities":["CODE_READ"],"reason":"需要项目证据"}
                """);

        assertThat(classifier.classify(List.of(), "结合上下文继续分析"))
                .isEqualTo(new TaskDecision(
                        TaskRoute.KNOWLEDGE,
                        TaskKind.PROJECT_QUERY,
                        TaskComplexity.STANDARD,
                        Set.of(RequiredCapability.CODE_READ),
                        "需要项目证据"));
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
