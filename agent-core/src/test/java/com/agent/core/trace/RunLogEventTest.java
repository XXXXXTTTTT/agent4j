package com.agent.core.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunLogEventTest {

    private static final UUID EVENT_ID = UUID.fromString(
            "8e45bc89-69a7-40c4-b0fa-d11fb27bd249");
    private static final UUID RUN_ID = UUID.fromString(
            "b6a87458-730a-4336-80fb-fc2c058b6416");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-02T01:00:00Z");

    @Test
    void roundTripsExactLogEvent() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        RunLogEvent event = new RunLogEvent(
                EVENT_ID,
                RUN_ID,
                "ops",
                0,
                RunLogStream.PTY,
                "\u001b[32mok\u001b[0m",
                OCCURRED_AT);

        String json = objectMapper.writeValueAsString(event);

        assertThat(objectMapper.readValue(json, RunLogEvent.class)).isEqualTo(event);
        assertThat(RunLogStream.values())
                .containsExactly(RunLogStream.STDOUT, RunLogStream.STDERR, RunLogStream.PTY);
    }

    @Test
    void validatesEveryLogEventField() {
        assertThatThrownBy(() -> event(null, RUN_ID, "ops", 0, RunLogStream.PTY, "", OCCURRED_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> event(EVENT_ID, null, "ops", 0, RunLogStream.PTY, "", OCCURRED_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> event(EVENT_ID, RUN_ID, " ", 0, RunLogStream.PTY, "", OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> event(EVENT_ID, RUN_ID, "ops", -1, RunLogStream.PTY, "", OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> event(EVENT_ID, RUN_ID, "ops", 0, null, "", OCCURRED_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> event(EVENT_ID, RUN_ID, "ops", 0, RunLogStream.PTY, null, OCCURRED_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> event(EVENT_ID, RUN_ID, "ops", 0, RunLogStream.PTY, "", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void noOpPublisherAcceptsEvent() {
        RunLogPublisher.noop().publish(event(
                EVENT_ID, RUN_ID, "ops", 0, RunLogStream.STDOUT, "", OCCURRED_AT));
    }

    private static RunLogEvent event(
            UUID eventId,
            UUID runId,
            String nodeName,
            long sequence,
            RunLogStream stream,
            String text,
            Instant occurredAt) {
        return new RunLogEvent(
                eventId, runId, nodeName, sequence, stream, text, occurredAt);
    }
}
