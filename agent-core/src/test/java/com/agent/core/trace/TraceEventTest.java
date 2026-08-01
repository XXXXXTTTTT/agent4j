package com.agent.core.trace;

import com.agent.core.engine.InterruptRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceEventTest {

    private static final UUID EVENT_ID = UUID.fromString("1ddd5c1d-a140-49ac-b452-9e5390524253");
    private static final UUID RUN_ID = UUID.fromString("34af026e-0b87-4cb4-93f8-bcf4a1130285");
    private static final UUID INTERRUPT_ID = UUID.fromString("56613819-20c5-4e66-afae-f7dfd64e75aa");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-01T07:00:00Z");
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void serializesAndDeserializesEveryEventWithExactType() throws Exception {
        InterruptRequest request = new InterruptRequest(
                INTERRUPT_ID,
                "ops",
                "需要人工审批",
                Map.of("command", "mvn verify"));
        List<TraceEvent> events = List.of(
                new TraceEvent.NodeStarted(EVENT_ID, RUN_ID, 0, OCCURRED_AT, "coder"),
                new TraceEvent.NodeCompleted(
                        EVENT_ID, RUN_ID, 1, OCCURRED_AT, "coder", "ops"),
                new TraceEvent.Interrupted(
                        EVENT_ID, RUN_ID, 2, OCCURRED_AT, "ops", request),
                new TraceEvent.Approved(
                        EVENT_ID, RUN_ID, 3, OCCURRED_AT, "ops", "已核对"),
                new TraceEvent.Rejected(
                        EVENT_ID, RUN_ID, 3, OCCURRED_AT, "ops", "拒绝执行"),
                new TraceEvent.Failed(
                        EVENT_ID,
                        RUN_ID,
                        4,
                        OCCURRED_AT,
                        "java.io.IOException: failed\n\tat Test.run(Test.java:1)"),
                new TraceEvent.Completed(EVENT_ID, RUN_ID, 4, OCCURRED_AT));

        assertThat(events).extracting(TraceEvent::type)
                .containsExactly(TraceEventType.values());
        for (TraceEvent event : events) {
            String json = objectMapper.writeValueAsString(event);
            JsonNode tree = objectMapper.readTree(json);
            assertThat(tree.path("type").textValue()).isEqualTo(event.type().name());
            assertThat(tree.path("eventId").textValue()).isEqualTo(EVENT_ID.toString());
            assertThat(tree.path("runId").textValue()).isEqualTo(RUN_ID.toString());
            assertThat(objectMapper.readValue(json, TraceEvent.class)).isEqualTo(event);
        }
    }

    @Test
    void validatesCommonAndSpecificFields() {
        InterruptRequest request = new InterruptRequest(
                INTERRUPT_ID, "ops", "需要审批", Map.of());

        assertThatThrownBy(() -> new TraceEvent.NodeStarted(
                null, RUN_ID, 0, OCCURRED_AT, "coder"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TraceEvent.NodeStarted(
                EVENT_ID, null, 0, OCCURRED_AT, "coder"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TraceEvent.NodeStarted(
                EVENT_ID, RUN_ID, -1, OCCURRED_AT, "coder"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TraceEvent.NodeStarted(
                EVENT_ID, RUN_ID, 0, null, "coder"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TraceEvent.NodeStarted(
                EVENT_ID, RUN_ID, 0, OCCURRED_AT, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TraceEvent.NodeCompleted(
                EVENT_ID, RUN_ID, 0, OCCURRED_AT, "coder", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TraceEvent.Interrupted(
                EVENT_ID, RUN_ID, 0, OCCURRED_AT, "coder", request))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TraceEvent.Approved(
                EVENT_ID, RUN_ID, 0, OCCURRED_AT, "ops", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TraceEvent.Rejected(
                EVENT_ID, RUN_ID, 0, OCCURRED_AT, "ops", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TraceEvent.Failed(
                EVENT_ID, RUN_ID, 0, OCCURRED_AT, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noOpPublisherAcceptsEvents() {
        TraceEvent event = new TraceEvent.Completed(EVENT_ID, RUN_ID, 0, OCCURRED_AT);

        TraceEventPublisher.noop().publish(event);
    }
}
