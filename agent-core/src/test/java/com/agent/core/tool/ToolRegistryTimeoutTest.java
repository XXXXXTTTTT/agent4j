package com.agent.core.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistryTimeoutTest {

    @Test
    void interruptsBlockingHandlerAndReturnsWithoutWaiting() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interruptedLatch = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        try (DefaultToolRegistry registry = new DefaultToolRegistry(
                new JacksonToolSchemaValidator(), new DefaultToolAuthorizer(), event -> {
                }, new ObjectMapper(), System::nanoTime)) {
            registry.register(new ToolDefinition(
                    "blocking.tool", "阻塞工具", JsonNodeFactory.instance.objectNode().put("type", "object"),
                    Set.of(), ToolRiskLevel.LOW, Duration.ofMillis(40), (call, context) -> {
                        started.countDown();
                        try {
                            Thread.sleep(Duration.ofSeconds(5));
                        } catch (InterruptedException exception) {
                            interrupted.set(true);
                            interruptedLatch.countDown();
                            throw exception;
                        }
                        return JsonNodeFactory.instance.objectNode();
                    }));
            long start = System.nanoTime();
            ToolResult result = registry.execute(
                    new ToolCall("timeout-1", "blocking.tool", JsonNodeFactory.instance.objectNode()),
                    new ToolInvocationContext(UUID.randomUUID(), "ops", "user-a", Path.of("."), Set.of(), false));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(result.status()).isEqualTo(ToolResultStatus.TIMED_OUT);
            assertThat(result.errorStack()).contains("ToolTimeoutException");
            assertThat(elapsedMs).isLessThan(1_000);
            assertThat(interruptedLatch.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted).isTrue();
        }
    }
}
