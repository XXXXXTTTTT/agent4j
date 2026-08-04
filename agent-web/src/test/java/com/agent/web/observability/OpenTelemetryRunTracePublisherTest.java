package com.agent.web.observability;

import com.agent.core.engine.NodeExecutionContext;
import com.agent.core.llm.TaskType;
import com.agent.core.observability.ModelCallSpan;
import com.agent.core.observability.ModelCallStart;
import com.agent.core.observability.ModelCallSuccess;
import com.agent.core.observability.ModelUsage;
import com.agent.core.trace.TraceEvent;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenTelemetryRunTracePublisherTest {

    private static final Instant START = Instant.parse("2026-08-04T10:00:00Z");

    @Test
    void exportsRunNodeGenerationTopologyAndAttributes() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        try (SdkTracerProvider provider = provider(exporter)) {
            Tracer tracer = provider.get("test");
            OpenTelemetryRunTracePublisher publisher =
                    new OpenTelemetryRunTracePublisher(tracer);
            UUID runId = UUID.randomUUID();
            publisher.publish(new TraceEvent.NodeStarted(
                    UUID.randomUUID(), runId, 7, START, "coder"));

            ModelCallSpan generation = publisher.start(new ModelCallStart(
                    Optional.of(new NodeExecutionContext(runId, "coder")),
                    TaskType.CODE,
                    "primary",
                    "code-model"));
            generation.succeed(new ModelCallSuccess(
                    Optional.of("actual-code-model"),
                    Optional.of(new ModelUsage(11, 7, 18))));
            generation.close();
            publisher.publish(new TraceEvent.NodeCompleted(
                    UUID.randomUUID(), runId, 8, START.plusSeconds(1), "coder", "ops"));
            publisher.publish(new TraceEvent.Completed(
                    UUID.randomUUID(), runId, 8, START.plusSeconds(2)));
            provider.forceFlush().join(5, TimeUnit.SECONDS);

            List<SpanData> spans = exporter.getFinishedSpanItems();
            assertThat(spans).hasSize(3);
            SpanData run = span(spans, "agent.run");
            SpanData node = span(spans, "agent.node coder");
            SpanData generationData = span(spans, "chat code-model");
            assertThat(node.getParentSpanId()).isEqualTo(run.getSpanId());
            assertThat(generationData.getParentSpanId()).isEqualTo(node.getSpanId());
            assertThat(run.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("langfuse.trace.name")))
                    .isEqualTo("agent.run");
            assertThat(run.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("langfuse.session.id")))
                    .isEqualTo(runId.toString());
            assertThat(run.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.longKey("agent.checkpoint.version")))
                    .isEqualTo(7L);
            assertThat(run.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey(
                            "langfuse.trace.metadata.checkpoint_version")))
                    .isEqualTo("7");
            assertThat(node.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("agent.node.name")))
                    .isEqualTo("coder");
            assertThat(node.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("agent.next_node")))
                    .isEqualTo("ops");
            assertThat(generationData.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey(
                            "langfuse.observation.type"))).isEqualTo("generation");
            assertThat(generationData.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey(
                            "gen_ai.operation.name"))).isEqualTo("chat");
            assertThat(generationData.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey(
                            "gen_ai.request.model"))).isEqualTo("code-model");
            assertThat(generationData.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey(
                            "gen_ai.response.model"))).isEqualTo("actual-code-model");
            assertThat(generationData.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.longKey(
                            "gen_ai.usage.input_tokens"))).isEqualTo(11L);
            assertThat(generationData.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.longKey(
                            "gen_ai.usage.output_tokens"))).isEqualTo(7L);
            assertThat(generationData.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.longKey(
                            "agent.model.total_tokens"))).isEqualTo(18L);
            assertThat(generationData.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey(
                            "agent.model.endpoint"))).isEqualTo("primary");
            assertThat(generationData.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey(
                            "agent.model.task_type"))).isEqualTo("CODE");
        }
    }

    @Test
    void marksActiveRunAndNodeAsFailed() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        try (SdkTracerProvider provider = provider(exporter)) {
            OpenTelemetryRunTracePublisher publisher =
                    new OpenTelemetryRunTracePublisher(provider.get("test"));
            UUID runId = UUID.randomUUID();
            publisher.publish(new TraceEvent.NodeStarted(
                    UUID.randomUUID(), runId, 1, START, "coder"));
            publisher.publish(new TraceEvent.Failed(
                    UUID.randomUUID(), runId, 2, START.plusSeconds(1), "stack trace"));
            provider.forceFlush().join(5, TimeUnit.SECONDS);

            assertThat(exporter.getFinishedSpanItems()).allSatisfy(span ->
                    assertThat(span.getStatus().getStatusCode())
                            .isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR));
        }
    }

    @Test
    void startsNewRunSegmentAfterInterruptAndApproval() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        try (SdkTracerProvider provider = provider(exporter)) {
            OpenTelemetryRunTracePublisher publisher =
                    new OpenTelemetryRunTracePublisher(provider.get("test"));
            UUID runId = UUID.randomUUID();
            publisher.publish(new TraceEvent.NodeStarted(
                    UUID.randomUUID(), runId, 1, START, "coder"));
            publisher.publish(new TraceEvent.Interrupted(
                    UUID.randomUUID(), runId, 2, START.plusSeconds(1), "coder",
                    new com.agent.core.engine.InterruptRequest(
                            UUID.randomUUID(), "coder", "dangerous operation", java.util.Map.of())));
            publisher.publish(new TraceEvent.Approved(
                    UUID.randomUUID(), runId, 3, START.plusSeconds(2),
                    "coder", "approved"));
            publisher.publish(new TraceEvent.NodeStarted(
                    UUID.randomUUID(), runId, 4, START.plusSeconds(3), "coder"));
            publisher.publish(new TraceEvent.NodeCompleted(
                    UUID.randomUUID(), runId, 5, START.plusSeconds(4), "coder", "ops"));
            publisher.publish(new TraceEvent.Completed(
                    UUID.randomUUID(), runId, 5, START.plusSeconds(5)));
            provider.forceFlush().join(5, TimeUnit.SECONDS);

            List<SpanData> runs = exporter.getFinishedSpanItems().stream()
                    .filter(span -> span.getName().equals("agent.run"))
                    .toList();
            assertThat(runs).hasSize(2);
            assertThat(runs).extracting(span -> span.getAttributes().get(
                            io.opentelemetry.api.common.AttributeKey.stringKey(
                                    "langfuse.session.id")))
                    .containsOnly(runId.toString());
            assertThat(runs.get(0).getSpanId()).isNotEqualTo(runs.get(1).getSpanId());
        }
    }

    @Test
    void rejectsOutOfOrderEventsAndDoubleGenerationTermination() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        try (SdkTracerProvider provider = provider(exporter)) {
            OpenTelemetryRunTracePublisher publisher =
                    new OpenTelemetryRunTracePublisher(provider.get("test"));
            UUID runId = UUID.randomUUID();
            assertThatThrownBy(() -> publisher.publish(new TraceEvent.NodeCompleted(
                    UUID.randomUUID(), runId, 1, START, "coder", "ops")))
                    .isInstanceOf(IllegalStateException.class);

            ModelCallSpan generation = publisher.start(new ModelCallStart(
                    Optional.empty(), TaskType.CODE, "primary", "code-model"));
            generation.close();
            assertThatThrownBy(generation::close)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void closeEndsActiveSpans() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        try (SdkTracerProvider provider = provider(exporter)) {
            OpenTelemetryRunTracePublisher publisher =
                    new OpenTelemetryRunTracePublisher(provider.get("test"));
            publisher.publish(new TraceEvent.NodeStarted(
                    UUID.randomUUID(), UUID.randomUUID(), 1, START, "coder"));
            publisher.close();
            provider.forceFlush().join(5, TimeUnit.SECONDS);

            assertThat(exporter.getFinishedSpanItems()).hasSize(2);
        }
    }

    private static SdkTracerProvider provider(InMemorySpanExporter exporter) {
        return SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
    }

    private static SpanData span(List<SpanData> spans, String name) {
        return spans.stream()
                .filter(item -> item.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
