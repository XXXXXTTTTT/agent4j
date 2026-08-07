package com.agent.core.intent;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskDecisionKnowledgeTest {

    @Test
    void acceptsOnlyCodeReadForProjectKnowledgeQueries() {
        TaskDecision decision = new TaskDecision(
                TaskRoute.KNOWLEDGE,
                TaskKind.PROJECT_QUERY,
                TaskComplexity.STANDARD,
                Set.of(RequiredCapability.CODE_READ),
                "需要读取当前项目知识");

        assertThat(decision.requiredCapabilities())
                .containsExactly(RequiredCapability.CODE_READ);
    }

    @Test
    void rejectsKnowledgeRouteWithoutProjectQueryKind() {
        assertThatThrownBy(() -> new TaskDecision(
                TaskRoute.KNOWLEDGE,
                TaskKind.CHAT,
                TaskComplexity.SIMPLE,
                Set.of(RequiredCapability.CODE_READ),
                "冲突"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KNOWLEDGE");
    }

    @Test
    void rejectsKnowledgeRouteWithWriteCapability() {
        assertThatThrownBy(() -> new TaskDecision(
                TaskRoute.KNOWLEDGE,
                TaskKind.PROJECT_QUERY,
                TaskComplexity.STANDARD,
                Set.of(RequiredCapability.CODE_READ, RequiredCapability.CODE_WRITE),
                "冲突"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CODE_READ");
    }

    @Test
    void rejectsAgentRouteForProjectQueryKind() {
        assertThatThrownBy(() -> new TaskDecision(
                TaskRoute.AGENT,
                TaskKind.PROJECT_QUERY,
                TaskComplexity.STANDARD,
                Set.of(RequiredCapability.CODE_READ),
                "冲突"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AGENT");
    }
}
