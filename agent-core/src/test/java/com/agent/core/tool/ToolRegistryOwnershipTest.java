package com.agent.core.tool;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

    @Test
    void keepsOwnerDrainingUntilTimedOutHandlerIgnoringInterruptActuallyExits() throws Exception {
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch allowHandlerExit = new CountDownLatch(1);
        CountDownLatch handlerExited = new CountDownLatch(1);
        try (DefaultToolRegistry registry = new DefaultToolRegistry()) {
            registry.registerOwned("installation-a", List.of(new ToolDefinition(
                    "ignoring-interrupt.tool", "忽略中断工具",
                    JsonNodeFactory.instance.objectNode().put("type", "object"), Set.of(), ToolRiskLevel.LOW,
                    Duration.ofMillis(20), (call, context) -> {
                        handlerStarted.countDown();
                        while (allowHandlerExit.getCount() > 0) {
                            try {
                                allowHandlerExit.await();
                            } catch (InterruptedException ignored) {
                                // 测试 handler 忽略取消中断，直到显式允许退出。
                            }
                        }
                        handlerExited.countDown();
                        return JsonNodeFactory.instance.objectNode();
                    })));

            ToolResult timedOut = registry.execute(call("timeout", "ignoring-interrupt.tool"), context());
            assertThat(handlerStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(timedOut.status()).isEqualTo(ToolResultStatus.TIMED_OUT);
            registry.beginDrain("installation-a");
            registry.unregisterOwned("installation-a", Duration.ofMillis(20));
            assertThat(registry.find("ignoring-interrupt.tool")).isPresent();

            allowHandlerExit.countDown();
            assertThat(handlerExited.await(1, TimeUnit.SECONDS)).isTrue();
            registry.unregisterOwned("installation-a", Duration.ofSeconds(1));
            assertThat(registry.find("ignoring-interrupt.tool")).isEmpty();
        }
    }

    @Test
    void createsLifecycleForEmptyOwnerRegistration() {
        try (DefaultToolRegistry registry = new DefaultToolRegistry()) {
            registry.registerOwned("empty-installation", List.of());

            registry.beginDrain("empty-installation");
            registry.unregisterOwned("empty-installation", Duration.ZERO);
            registry.registerOwned("empty-installation", List.of(definition("after-empty.tool")));

            assertThat(registry.find("after-empty.tool")).isPresent();
        }
    }

    @Test
    void doesNotHoldLifecycleMonitorWhileAuditingDrainedCall() throws Exception {
        CountDownLatch auditStarted = new CountDownLatch(1);
        CountDownLatch allowAuditReturn = new CountDownLatch(1);
        try (DefaultToolRegistry registry = new DefaultToolRegistry(
                new JacksonToolSchemaValidator(), new DefaultToolAuthorizer(), event -> {
                    auditStarted.countDown();
                    try {
                        if (!allowAuditReturn.await(1, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("审计未收到放行信号");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("审计等待被中断", exception);
                    }
                }, new ObjectMapper(), System::nanoTime)) {
            registry.registerOwned("installation-a", List.of(definition("drained.tool")));
            registry.beginDrain("installation-a");

            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                Future<ToolResult> rejected = executor.submit(
                        () -> registry.execute(call("drained", "drained.tool"), context()));
                assertThat(auditStarted.await(1, TimeUnit.SECONDS)).isTrue();

                Future<?> unregistered = executor.submit(
                        () -> registry.unregisterOwned("installation-a", Duration.ZERO));
                assertThat(unregistered.get(500, TimeUnit.MILLISECONDS)).isNull();
                allowAuditReturn.countDown();
                assertThat(rejected.get(1, TimeUnit.SECONDS).status()).isEqualTo(ToolResultStatus.FAILED);
            }
            assertThat(registry.find("drained.tool")).isEmpty();
        } finally {
            allowAuditReturn.countDown();
        }
    }

    @Test
    void releasesOwnerWhenTimedOutTaskIsCancelledBeforeHandlerStarts() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        try (DefaultToolRegistry registry = registry(executor)) {
            registry.registerOwned("installation-a", List.of(new ToolDefinition(
                    "queued.tool", "排队工具", JsonNodeFactory.instance.objectNode().put("type", "object"), Set.of(),
                    ToolRiskLevel.LOW, Duration.ofMillis(20), (call, context) -> JsonNodeFactory.instance.objectNode())));

            ToolResult result = registry.execute(call("queued", "queued.tool"), context());
            assertThat(result.status()).isEqualTo(ToolResultStatus.TIMED_OUT);
            assertThat(executor.submitted.await(1, TimeUnit.SECONDS)).isTrue();
            registry.beginDrain("installation-a");
            registry.unregisterOwned("installation-a", Duration.ZERO);

            assertThat(registry.find("queued.tool")).isEmpty();
        }
    }

    @Test
    void doesNotReleaseOwnerWhenTimedOutHandlerHasStartedAndIgnoresInterrupt() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch allowHandlerExit = new CountDownLatch(1);
        try (DefaultToolRegistry registry = registry(executor);
             var callers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            registry.registerOwned("installation-a", List.of(new ToolDefinition(
                    "started.tool", "已启动工具", JsonNodeFactory.instance.objectNode().put("type", "object"), Set.of(),
                    ToolRiskLevel.LOW, Duration.ofMillis(20), (call, context) -> {
                        handlerStarted.countDown();
                        while (allowHandlerExit.getCount() > 0) {
                            try {
                                allowHandlerExit.await();
                            } catch (InterruptedException ignored) {
                                // 测试 handler 忽略取消中断，直到显式允许退出。
                            }
                        }
                        return JsonNodeFactory.instance.objectNode();
                    })));

            Future<ToolResult> result = callers.submit(() -> registry.execute(call("started", "started.tool"), context()));
            assertThat(executor.submitted.await(1, TimeUnit.SECONDS)).isTrue();
            executor.startNext();
            assertThat(handlerStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(result.get(1, TimeUnit.SECONDS).status()).isEqualTo(ToolResultStatus.TIMED_OUT);
            registry.beginDrain("installation-a");
            registry.unregisterOwned("installation-a", Duration.ofMillis(20));
            assertThat(registry.find("started.tool")).isPresent();

            allowHandlerExit.countDown();
            registry.unregisterOwned("installation-a", Duration.ofSeconds(1));
            assertThat(registry.find("started.tool")).isEmpty();
        }
    }

    private DefaultToolRegistry registry(ManualExecutor executor) {
        return new DefaultToolRegistry(new JacksonToolSchemaValidator(), new DefaultToolAuthorizer(), ToolAuditSink.noop(),
                new ObjectMapper(), System::nanoTime, new com.agent.core.security.DefaultToolParameterPolicy(java.util.Map.of()),
                new com.agent.core.security.DefaultOutputRedactor(), com.agent.core.security.SecurityViolationSink.noop(), executor);
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        private final CountDownLatch submitted = new CountDownLatch(1);
        private final java.util.concurrent.BlockingQueue<Runnable> tasks = new java.util.concurrent.LinkedBlockingQueue<>();
        private final AtomicBoolean shutdown = new AtomicBoolean();

        @Override
        public void execute(Runnable command) {
            if (shutdown.get()) {
                throw new java.util.concurrent.RejectedExecutionException("执行器已关闭");
            }
            tasks.add(command);
            submitted.countDown();
        }

        void startNext() throws InterruptedException {
            Runnable task = tasks.poll(1, TimeUnit.SECONDS);
            if (task == null) {
                throw new AssertionError("没有待执行任务");
            }
            Thread.startVirtualThread(task);
        }

        @Override public void shutdown() { shutdown.set(true); }
        @Override public java.util.List<Runnable> shutdownNow() { shutdown.set(true); return java.util.List.copyOf(tasks); }
        @Override public boolean isShutdown() { return shutdown.get(); }
        @Override public boolean isTerminated() { return shutdown.get(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown.get(); }
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
