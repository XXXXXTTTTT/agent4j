package com.agent.web.trace;

import com.agent.core.multiagent.AgentHandoffEvent;
import com.agent.core.trace.TraceEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionHandoffTraceEventPublisherTest {

    @Test
    void mapsHandoffToParentRunTraceWithoutChildReasoningPayload() {
        UUID taskId = UUID.randomUUID();
        UUID parentRunId = UUID.randomUUID();
        UUID childRunId = UUID.randomUUID();
        List<TraceEvent> traces = new ArrayList<>();
        ProductionHandoffTraceEventPublisher publisher = new ProductionHandoffTraceEventPublisher(traces::add);

        publisher.publish(new AgentHandoffEvent.Started(
                taskId, parentRunId, childRunId, "coordinator", "researcher",
                Instant.parse("2026-08-14T08:00:00Z")));

        assertThat(traces).singleElement().isInstanceOfSatisfying(TraceEvent.Handoff.class, event -> {
            assertThat(event.runId()).isEqualTo(parentRunId);
            assertThat(event.parentRunId()).isEqualTo(parentRunId);
            assertThat(event.childRunId()).isEqualTo(childRunId);
            assertThat(event.fromAgent()).isEqualTo("coordinator");
            assertThat(event.toAgent()).isEqualTo("researcher");
            assertThat(event.lifecycle()).isEqualTo("STARTED");
            assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-14T08:00:00Z"));
        });
    }

    @Test
    void mapsEveryGovernedLifecycleName() {
        UUID taskId = UUID.randomUUID();
        UUID parentRunId = UUID.randomUUID();
        UUID childRunId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-08-14T08:00:00Z");
        List<TraceEvent> traces = new ArrayList<>();
        ProductionHandoffTraceEventPublisher publisher = new ProductionHandoffTraceEventPublisher(traces::add);

        publisher.publish(new AgentHandoffEvent.NodeStarted(taskId, parentRunId, childRunId, "coordinator", "researcher", occurredAt, "research"));
        publisher.publish(new AgentHandoffEvent.NodeProgress(taskId, parentRunId, childRunId, "coordinator", "researcher", occurredAt, "research", "read-only"));
        publisher.publish(new AgentHandoffEvent.NodeCompleted(taskId, parentRunId, childRunId, "coordinator", "researcher", occurredAt, "research", "end"));
        publisher.publish(new AgentHandoffEvent.Completed(taskId, parentRunId, childRunId, "coordinator", "researcher", occurredAt, java.time.Duration.ZERO));
        publisher.publish(new AgentHandoffEvent.Failed(taskId, parentRunId, childRunId, "coordinator", "researcher", occurredAt, "failure"));

        assertThat(traces).extracting(event -> ((TraceEvent.Handoff) event).lifecycle())
                .containsExactly("NODE_STARTED", "NODE_PROGRESS", "NODE_COMPLETED", "COMPLETED", "FAILED");
    }
}
