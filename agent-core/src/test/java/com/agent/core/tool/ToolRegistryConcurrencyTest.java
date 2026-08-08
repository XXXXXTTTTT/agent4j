package com.agent.core.tool;

import com.agent.core.intent.RequiredCapability;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryConcurrencyTest {

    @Test
    void concurrentRegistrationAcceptsOnlyOneDefinition() throws Exception {
        try (DefaultToolRegistry registry = new DefaultToolRegistry()) {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Throwable> failures = new CopyOnWriteArrayList<>();
            for (int i = 0; i < 2; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        registry.register(definition("same.tool"));
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                    return null;
                });
            }
            assertThat(ready.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            executor.close();
            assertThat(registry.list()).extracting(ToolDefinition::name).containsExactly("same.tool");
            assertThat(failures).hasSize(1).allSatisfy(failure ->
                    assertThat(failure).isInstanceOf(ToolRegistrationException.class));
        }
    }

    @Test
    void concurrentCallsDoNotShareArgumentsOrOutputsAndAuditOnceEach() throws Exception {
        List<ToolAuditEvent> events = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        try (DefaultToolRegistry registry = new DefaultToolRegistry(
                new JacksonToolSchemaValidator(), new DefaultToolAuthorizer(), events::add,
                new ObjectMapper(), System::nanoTime)) {
            registry.register(new ToolDefinition("echo.tool", "回显工具",
                    JsonNodeFactory.instance.objectNode().put("type", "object"), Set.of(),
                    ToolRiskLevel.LOW, Duration.ofSeconds(2), (call, context) -> {
                        calls.incrementAndGet();
                        ObjectNode result = JsonNodeFactory.instance.objectNode();
                        result.set("value", call.arguments().path("value"));
                        return result;
                    }));
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            List<java.util.concurrent.Future<ToolResult>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                int index = i;
                ObjectNode args = JsonNodeFactory.instance.objectNode().put("value", i);
                futures.add(executor.submit(() -> registry.execute(
                        new ToolCall("call-" + index, "echo.tool", args), context())));
            }
            List<ToolResult> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get());
            }
            executor.close();

            assertThat(calls).hasValue(20);
            assertThat(results).allMatch(result -> result.status() == ToolResultStatus.SUCCEEDED);
            assertThat(results).extracting(result -> result.output().path("value").asInt())
                    .containsExactlyInAnyOrderElementsOf(java.util.stream.IntStream.range(0, 20).boxed().toList());
            assertThat(events).hasSize(20);
        }
    }

    private ToolDefinition definition(String name) {
        return new ToolDefinition(name, "并发工具",
                JsonNodeFactory.instance.objectNode().put("type", "object"), Set.of(),
                ToolRiskLevel.LOW, Duration.ofSeconds(1), (call, context) -> JsonNodeFactory.instance.objectNode());
    }

    private ToolInvocationContext context() {
        return new ToolInvocationContext(UUID.randomUUID(), "ops", "user-a", Path.of("."),
                Set.of(RequiredCapability.CODE_READ), false);
    }
}
