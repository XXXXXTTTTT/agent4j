package com.agent.core.gui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrowserActionDecisionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesExactClickFillScrollAndDoneDocuments() throws Exception {
        BrowserActionDecision click = parse(document(
                "click", "#submit", "", 0, "#result", "提交表单", "", List.of()));
        BrowserActionDecision fill = parse(document(
                "fill", "#name", "Agent4J", 0, "#name", "填写名称", "", List.of()));
        BrowserActionDecision scroll = parse(document(
                "scroll", "", "", 500, "page", "查看下方内容", "", List.of()));
        BrowserActionDecision done = parse(document(
                "done", "", "", 0, "", "目标已完成", "提交成功", List.of("evidence-2")));

        assertThat(click.action()).isEqualTo(BrowserActionDecision.Action.CLICK);
        assertThat(click.selector()).isEqualTo("#submit");
        assertThat(fill.action()).isEqualTo(BrowserActionDecision.Action.FILL);
        assertThat(fill.value()).isEqualTo("Agent4J");
        assertThat(scroll.action()).isEqualTo(BrowserActionDecision.Action.SCROLL);
        assertThat(scroll.deltaY()).isEqualTo(500);
        assertThat(done.action()).isEqualTo(BrowserActionDecision.Action.DONE);
        assertThat(done.summary()).isEqualTo("提交成功");
        assertThat(done.evidenceRefs()).containsExactly("evidence-2");
    }

    @Test
    void rejectsMarkdownUnknownFieldsWrongTypesAndMissingFields() {
        assertThatThrownBy(() -> parse("```json\n" + document(
                "click", "#submit", "", 0, "page", "提交", "", List.of()) + "\n```"))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> parse(document(
                "click", "#submit", "", 0, "page", "提交", "", List.of())
                .replaceFirst("\\}$", ",\"unknown\":true}")))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("unknown");
        assertThatThrownBy(() -> parse(document(
                "scroll", "", "", 500, "page", "滚动", "", List.of())
                .replace("\"deltaY\":500", "\"deltaY\":\"500\"")))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("deltaY");
        assertThatThrownBy(() -> parse("""
                {"action":"click","value":"","deltaY":0,"evidenceSelector":"page",
                 "reason":"提交","summary":"","evidenceRefs":[]}
                """))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("selector");
    }

    @Test
    void enforcesActionOwnedFieldsAndScrollBounds() throws Exception {
        assertThatThrownBy(() -> parse(document(
                "click", "#submit", "unexpected", 0, "page", "提交", "", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("value");
        assertThatThrownBy(() -> parse(document(
                "fill", "#name", "Agent4J", 1, "page", "填写", "", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deltaY");
        assertThatThrownBy(() -> parse(document(
                "scroll", "", "", 0, "page", "滚动", "", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deltaY");
        assertThatThrownBy(() -> parse(document(
                "scroll", "", "", 10_001, "page", "滚动", "", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deltaY");
        assertThatThrownBy(() -> parse(document(
                "done", "", "", 0, "page", "完成", "完成", List.of("evidence-1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidenceSelector");
        assertThatThrownBy(() -> parse(document(
                "click", "#submit", "", 0, "page", "提交", "unexpected", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("summary");
    }

    @Test
    void requiresDoneSummaryAndEvidenceAndDefensivelyCopiesReferences() throws Exception {
        assertThatThrownBy(() -> parse(document(
                "done", "", "", 0, "", "完成", "", List.of("evidence-1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("summary");
        assertThatThrownBy(() -> parse(document(
                "done", "", "", 0, "", "完成", "完成", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidenceRefs");

        java.util.ArrayList<String> references = new java.util.ArrayList<>(List.of("evidence-1"));
        BrowserActionDecision decision = new BrowserActionDecision(
                BrowserActionDecision.Action.DONE,
                "",
                "",
                0,
                "",
                "完成",
                "完成",
                references);
        references.add("evidence-2");

        assertThat(decision.evidenceRefs()).containsExactly("evidence-1");
        assertThatThrownBy(() -> decision.evidenceRefs().add("evidence-3"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private BrowserActionDecision parse(String json) throws Exception {
        return BrowserActionDecision.parse(objectMapper, json);
    }

    private String document(
            String action,
            String selector,
            String value,
            int deltaY,
            String evidenceSelector,
            String reason,
            String summary,
            List<String> evidenceRefs) throws Exception {
        return objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("action", action)
                .put("selector", selector)
                .put("value", value)
                .put("deltaY", deltaY)
                .put("evidenceSelector", evidenceSelector)
                .put("reason", reason)
                .put("summary", summary)
                .set("evidenceRefs", objectMapper.valueToTree(evidenceRefs)));
    }
}
