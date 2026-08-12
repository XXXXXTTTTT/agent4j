package com.agent.core.tool;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryOwnershipTest {

    @Test
    void registersOwnerBatchAtomicallyAndRejectsNamesOwnedByAnotherOwner() {
        try (DefaultToolRegistry registry = new DefaultToolRegistry()) {
            long initialRevision = registry.revision();
            assertThatThrownBy(() -> registry.registerOwned("installation-a", List.of(
                    definition("first.tool"),
                    definition("first.tool"))))
                    .isInstanceOf(ToolRegistrationException.class);
            assertThat(registry.list()).isEmpty();
            assertThat(registry.revision()).isEqualTo(initialRevision);

            registry.registerOwned("installation-a", List.of(definition("shared.tool")));
            assertThatThrownBy(() -> registry.registerOwned("installation-b", List.of(definition("shared.tool"))))
                    .isInstanceOf(ToolRegistrationException.class);
            assertThat(registry.find("shared.tool")).isPresent();
        }
    }

    @Test
    void rejectsNewCallAfterDrainAndUnregistersAfterInFlightCallCompletes() throws Exception {
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch allowCompletion = new CountDownLatch(1);
        try (DefaultToolRegistry registry = new DefaultToolRegistry()) {
            registry.registerOwned("installation-a", List.of(new ToolDefinition(
                    "slow.tool", "慢速工具", JsonNodeFactory.instance.objectNode().put("type", "object"), Set.of(),
                    ToolRiskLevel.LOW, Duration.ofSeconds(2), (call, context) -> {
                        handlerStarted.countDown();
                        if (!allowCompletion.await(1, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("测试调用没有收到完成信号");
                        }
                        return JsonNodeFactory.instance.objectNode().put("ok", true);
                    })));

            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                Future<ToolResult> running = executor.submit(() -> registry.execute(call("running", "slow.tool"), context()));
                assertThat(handlerStarted.await(1, TimeUnit.SECONDS)).isTrue();
                registry.beginDrain("installation-a");
                ToolResult rejected = registry.execute(call("new", "slow.tool"), context());
                assertThat(rejected.status()).isEqualTo(ToolResultStatus.FAILED);
                assertThat(rejected.errorStack()).contains("正在停止");

                Future<?> unregistering = executor.submit(
                        () -> registry.unregisterOwned("installation-a", Duration.ofSeconds(1)));
                assertThat(registry.find("slow.tool")).isPresent();
                allowCompletion.countDown();
                assertThat(running.get(1, TimeUnit.SECONDS).status()).isEqualTo(ToolResultStatus.SUCCEEDED);
                unregistering.get(1, TimeUnit.SECONDS);
            }
            assertThat(registry.find("slow.tool")).isEmpty();
        }
    }

    @Test
    void keepsOwnerDrainingWhenUnregisterTimesOutAndRejectsBuiltinLifecycleChanges() throws Exception {
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch allowCompletion = new CountDownLatch(1);
        try (DefaultToolRegistry registry = new DefaultToolRegistry()) {
            registry.registerOwned("installation-a", List.of(new ToolDefinition(
                    "blocking.tool", "阻塞工具", JsonNodeFactory.instance.objectNode().put("type", "object"), Set.of(),
                    ToolRiskLevel.LOW, Duration.ofSeconds(2), (call, context) -> {
                        handlerStarted.countDown();
                        allowCompletion.await();
                        return JsonNodeFactory.instance.objectNode();
                    })));
            registry.register(definition("builtin.tool"));

            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                Future<ToolResult> running = executor.submit(
                        () -> registry.execute(call("running", "blocking.tool"), context()));
                assertThat(handlerStarted.await(1, TimeUnit.SECONDS)).isTrue();
                registry.beginDrain("installation-a");
                registry.unregisterOwned("installation-a", Duration.ofMillis(20));
                assertThat(registry.find("blocking.tool")).isPresent();
                assertThat(registry.execute(call("new", "blocking.tool"), context()).errorStack()).contains("正在停止");
                assertThatThrownBy(() -> registry.beginDrain("builtin")).isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> registry.unregisterOwned("builtin", Duration.ZERO))
                        .isInstanceOf(IllegalArgumentException.class);
                allowCompletion.countDown();
                assertThat(running.get(1, TimeUnit.SECONDS).status()).isEqualTo(ToolResultStatus.SUCCEEDED);
            }
        }
    }

    private ToolDefinition definition(String name) {
        return new ToolDefinition(name, "测试工具", JsonNodeFactory.instance.objectNode().put("type", "object"),
                Set.of(), ToolRiskLevel.LOW, Duration.ofSeconds(1),
                (call, context) -> JsonNodeFactory.instance.objectNode());
    }

    private ToolCall call(String callId, String name) {
        return new ToolCall(callId, name, JsonNodeFactory.instance.objectNode());
    }

    private ToolInvocationContext context() {
        return new ToolInvocationContext(UUID.fromString("00000000-0000-0000-0000-000000000022"),
                "ownership", "user-a", Path.of("."), Set.of(), false);
    }
}
