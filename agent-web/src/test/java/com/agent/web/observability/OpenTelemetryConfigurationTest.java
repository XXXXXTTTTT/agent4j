package com.agent.web.observability;

import com.agent.core.trace.TraceEvent;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenTelemetryConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(OpenTelemetryConfiguration.class);

    @Test
    void staysDisabledByDefaultAndProvidesNoopObserver() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(OpenTelemetryRunTracePublisher.class);
            assertThat(context).hasSingleBean(com.agent.core.observability.ModelCallObserver.class);
        });
    }

    @Test
    void rejectsInvalidEnabledConfiguration() {
        assertThatFailure("agent.observability.otlp-traces-endpoint");
        assertThatFailure(
                "agent.observability.otlp-traces-endpoint",
                "not-a-uri");
        assertThatFailure(
                "agent.observability.authorization",
                " ");
        assertThatFailure(
                "agent.observability.export-timeout",
                "0ms");
    }

    @Test
    void sendsCompleteOtlpHttpRequestToConfiguredEndpoint() throws IOException, InterruptedException {
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> ingestionVersion = new AtomicReference<>();
        AtomicReference<byte[]> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/public/otel/v1/traces", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            ingestionVersion.set(exchange.getRequestHeaders()
                    .getFirst("x-langfuse-ingestion-version"));
            body.set(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
            received.countDown();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort()
                    + "/api/public/otel/v1/traces";
            contextRunner
                    .withPropertyValues(
                            "agent.observability.enabled=true",
                            "agent.observability.service-name=test-service",
                            "agent.observability.otlp-traces-endpoint=" + endpoint,
                            "agent.observability.authorization=Basic test",
                            "agent.observability.export-timeout=5s")
                    .run(context -> {
                        OpenTelemetryRunTracePublisher publisher =
                                context.getBean(OpenTelemetryRunTracePublisher.class);
                        UUID runId = UUID.randomUUID();
                        publisher.publish(new TraceEvent.NodeStarted(
                                UUID.randomUUID(), runId, 1,
                                java.time.Instant.now(), "done"));
                        publisher.publish(new TraceEvent.NodeCompleted(
                                UUID.randomUUID(), runId, 2,
                                java.time.Instant.now(), "done", "__END__"));
                        publisher.publish(new TraceEvent.Completed(
                                UUID.randomUUID(), runId, 2,
                                java.time.Instant.now()));
                        context.getBean(SdkTracerProvider.class)
                                .forceFlush().join(10, TimeUnit.SECONDS);
                    });

            assertThat(received.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(method).hasValue("POST");
            assertThat(path).hasValue("/api/public/otel/v1/traces");
            assertThat(authorization).hasValue("Basic test");
            assertThat(ingestionVersion).hasValue("4");
            assertThat(body.get()).isNotNull().isNotEmpty();
        } finally {
            server.stop(0);
        }
    }

    private void assertThatFailure(String property) {
        assertThatFailure(property, "");
    }

    private void assertThatFailure(String property, String value) {
        contextRunner
                .withPropertyValues(
                        "agent.observability.enabled=true",
                        "agent.observability.otlp-traces-endpoint=http://127.0.0.1:4318/traces",
                        "agent.observability.authorization=Basic test",
                        "agent.observability.export-timeout=5s",
                        property + "=" + value)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining(property);
                });
    }
}
