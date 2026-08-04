package com.agent.web.observability;

import com.agent.core.engine.NodeExecutionContext;
import com.agent.core.llm.TaskType;
import com.agent.core.observability.ModelCallStart;
import com.agent.core.observability.ModelCallSuccess;
import com.agent.core.observability.ModelUsage;
import com.agent.core.trace.TraceEvent;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 Run、节点和模型调用观测协议的真实组合闭环。 */
class AgentObservabilityWorkflowTest {

    @Test
    void recordsRunNodeGenerationUsageAndCompletion() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            OpenTelemetryRunTracePublisher publisher =
                    new OpenTelemetryRunTracePublisher(provider.get("workflow"));
            UUID runId = UUID.randomUUID();
            Instant start = Instant.parse("2026-08-04T10:00:00Z");
            publisher.publish(new TraceEvent.NodeStarted(
                    UUID.randomUUID(), runId, 1, start, "planner"));
            var generation = publisher.start(new ModelCallStart(
                    Optional.of(new NodeExecutionContext(runId, "planner")),
                    TaskType.CODE, "primary", "planner-model"));
            generation.succeed(new ModelCallSuccess(
                    Optional.of("planner-model"), Optional.of(new ModelUsage(4, 6, 10))));
            generation.close();
            publisher.publish(new TraceEvent.NodeCompleted(
                    UUID.randomUUID(), runId, 2, start.plusSeconds(1), "planner", "end"));
            publisher.publish(new TraceEvent.Completed(
                    UUID.randomUUID(), runId, 2, start.plusSeconds(2)));
            provider.forceFlush().join(5, java.util.concurrent.TimeUnit.SECONDS);

            List<SpanData> spans = exporter.getFinishedSpanItems();
            assertThat(spans).extracting(SpanData::getName)
                    .containsExactlyInAnyOrder("agent.run", "agent.node planner", "chat planner-model");
            SpanData generationSpan = spans.stream()
                    .filter(span -> span.getName().equals("chat planner-model"))
                    .findFirst().orElseThrow();
            assertThat(generationSpan.getAttributes().get(
                    AttributeKey.longKey("agent.model.total_tokens"))).isEqualTo(10L);
            assertThat(generationSpan.getAttributes().get(
                    AttributeKey.stringKey("agent.model.endpoint"))).isEqualTo("primary");
        }
    }
}
