package com.agent.core.observability;

import com.agent.core.llm.TaskType;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class ModelObservationTest {

    @Test
    void rejectsMissingModelCallStartValues() {
        assertThatThrownBy(() -> new ModelCallStart(
                null, TaskType.CODE, "primary", "code-model"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("nodeContext");
        assertThatThrownBy(() -> new ModelCallStart(
                Optional.empty(), null, "primary", "code-model"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("taskType");
        assertThatThrownBy(() -> new ModelCallStart(
                Optional.empty(), TaskType.CODE, " ", "code-model"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpointName");
        assertThatThrownBy(() -> new ModelCallStart(
                Optional.empty(), TaskType.CODE, "primary", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestedModel");
    }

    @Test
    void rejectsInvalidModelUsage() {
        assertThatThrownBy(() -> new ModelUsage(-1, 0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("promptTokens");
        assertThatThrownBy(() -> new ModelUsage(0, -1, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("completionTokens");
        assertThatThrownBy(() -> new ModelUsage(0, 0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalTokens");
        assertThatThrownBy(() -> new ModelUsage(4, 5, 8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalTokens");
    }

    @Test
    void rejectsInvalidModelCallSuccessValues() {
        assertThatThrownBy(() -> new ModelCallSuccess(null, Optional.empty()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("responseModel");
        assertThatThrownBy(() -> new ModelCallSuccess(Optional.empty(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("usage");
        assertThatThrownBy(() -> new ModelCallSuccess(Optional.of(" "), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("responseModel");
    }

    @Test
    void providesNoopObserver() {
        ModelCallSpan span = ModelCallObserver.noop().start(new ModelCallStart(
                Optional.empty(), TaskType.CODE, "primary", "code-model"));

        assertThatCode(() -> {
            span.succeed(new ModelCallSuccess(Optional.empty(), Optional.empty()));
            span.fail(new IllegalStateException("observed"));
            span.close();
        }).doesNotThrowAnyException();
    }
}
