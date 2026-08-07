package com.agent.core.prompt;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptCatalogTest {

    @Test
    void rendersStaticAndDynamicSectionsWithStableFingerprint() {
        PromptCatalog catalog = new PromptCatalog(List.of(new PromptTemplate(
                "planner.chat",
                "1",
                "只回答问题。",
                "当前问题：{{task}}",
                Set.of("task"))));

        RenderedPrompt first = catalog.render(
                "planner.chat", "1", Map.of("task", "你是谁"));
        RenderedPrompt second = catalog.render(
                "planner.chat", "1", Map.of("task", "你是谁"));

        assertThat(first.staticSection()).isEqualTo("只回答问题。");
        assertThat(first.dynamicSection()).isEqualTo("当前问题：你是谁");
        assertThat(first.fingerprint())
                .matches("[0-9a-f]{64}")
                .isEqualTo(second.fingerprint());
    }

    @Test
    void rejectsMissingVariableAndUnknownVersion() {
        PromptCatalog catalog = new PromptCatalog(List.of(new PromptTemplate(
                "planner.chat", "1", "系统", "{{task}}", Set.of("task"))));

        assertThatThrownBy(() -> catalog.render("planner.chat", "1", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("task");
        assertThatThrownBy(() -> catalog.render(
                "planner.chat", "2", Map.of("task", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("planner.chat@2");
    }
}
