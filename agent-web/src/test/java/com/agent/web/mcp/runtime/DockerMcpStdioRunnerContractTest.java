package com.agent.web.mcp.runtime;

import com.agent.web.mcp.installation.WorkspaceMountMode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.AttachContainerCmd;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mockingDetails;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.slf4j.LoggerFactory.getLogger;

/** 不依赖 Docker Engine 的 runner 前置契约测试。 */
class DockerMcpStdioRunnerContractTest {
    @Test
    void rejectsEnvironmentOutsideAllowlistBeforeCreatingContainer() {
        DockerClient docker = mock(DockerClient.class);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try (DockerMcpStdioRunner runner = new DockerMcpStdioRunner(docker, executor)) {
            assertThatThrownBy(() -> runner.start(spec(Set.of("ALLOWED")), Map.of("DENIED", "secret"),
                    Path.of("D:/agent4j"), event -> { }))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("环境变量不在启动规范白名单");
            verifyNoInteractions(docker);
        }
    }

    @Test
    void propagatesSynchronousCreateFailureWithoutReplacingItDuringLocalCleanup() {
        DockerClient docker = mock(DockerClient.class);
        when(docker.createContainerCmd("alpine:3.20"))
                .thenThrow(new IllegalStateException("create failed"));
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try (DockerMcpStdioRunner runner = new DockerMcpStdioRunner(docker, executor)) {
            assertThatThrownBy(() -> runner.start(spec(Set.of()), Map.of(), Path.of("D:/agent4j"), event -> { }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("create failed");
        }
    }

    @Test
    void removesProcessAfterSynchronousAttachFailure() {
        DockerClient docker = mock(DockerClient.class);
        CreateContainerCmd create = mock(CreateContainerCmd.class, RETURNS_SELF);
        StartContainerCmd start = mock(StartContainerCmd.class);
        AttachContainerCmd attach = mock(AttachContainerCmd.class, RETURNS_SELF);
        LogContainerCmd logs = mock(LogContainerCmd.class, RETURNS_SELF);
        StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        CreateContainerResponse response = new CreateContainerResponse();
        response.setId("container-1");
        when(docker.createContainerCmd("alpine:3.20")).thenReturn(create);
        when(create.exec()).thenReturn(response);
        when(docker.startContainerCmd("container-1")).thenReturn(start);
        when(docker.attachContainerCmd("container-1")).thenReturn(attach);
        when(docker.logContainerCmd("container-1")).thenReturn(logs);
        when(docker.stopContainerCmd("container-1")).thenReturn(stop);
        when(docker.removeContainerCmd("container-1")).thenReturn(remove);
        when(attach.exec(any())).thenThrow(new IllegalStateException("attach failed"));
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try (DockerMcpStdioRunner runner = new DockerMcpStdioRunner(docker, executor)) {
            assertThatThrownBy(() -> runner.start(spec(Set.of()), Map.of(), Path.of("D:/agent4j"), event -> { }))
                    .isInstanceOf(IllegalStateException.class).hasMessage("attach failed");
        }
        verify(stop, times(1)).exec();
        verify(remove, times(1)).exec();
    }

    @Test
    void removesContainerAndPropagatesSynchronousStartFailure() {
        StartedRunner started = startableRunner(spec(Set.of()));
        when(started.start().exec()).thenThrow(new IllegalStateException("start failed"));
        try (DockerMcpStdioRunner runner = started.runner()) {
            assertThatThrownBy(() -> runner.start(spec(Set.of()), Map.of(), Path.of("D:/agent4j"), event -> { }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("start failed");
        }
        verify(started.stop(), times(1)).exec();
        verify(started.remove(), times(1)).exec();
    }

    @Test
    void treatsStoppedContainerStopNotModifiedAsIdempotentAndStillRemovesWithoutWarning() {
        StartedRunner started = startableRunner(spec(Set.of()));
        doThrow(new NotModifiedException("container already stopped")).when(started.stop()).exec();
        Logger logger = (Logger) getLogger(DockerMcpStdioRunner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        DockerMcpStdioRunner runner = started.runner();
        try {
            var process = runner.start(spec(Set.of()), Map.of(), Path.of("D:/agent4j"), event -> { });
            process.destroy();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        verify(started.stop()).exec();
        verify(started.remove()).exec();
        assertThat(appender.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN));
    }

    @Test
    void logsOtherStopCleanupFailuresAndStillRemovesContainer() {
        StartedRunner started = startableRunner(spec(Set.of()));
        doThrow(new IllegalStateException("stop failed")).when(started.stop()).exec();
        Logger logger = (Logger) getLogger(DockerMcpStdioRunner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try (DockerMcpStdioRunner runner = started.runner()) {
            var process = runner.start(spec(Set.of()), Map.of(), Path.of("D:/agent4j"), event -> { });
            process.destroy();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        verify(started.remove()).exec();
        assertThat(appender.list).anyMatch(event -> event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN));
    }

    @Test
    void startsContainerThenOpensStdinAttachAndLogReplayForStartupOutput() {
        StartedRunner started = startableRunner(spec(Set.of()));
        try (DockerMcpStdioRunner runner = started.runner()) {
            runner.start(spec(Set.of()), Map.of(), Path.of("D:/agent4j"), event -> { });
            var order = inOrder(started.start(), started.attach(), started.logs());
            order.verify(started.start()).exec();
            order.verify(started.attach()).withLogs(false);
            order.verify(started.attach()).withStdOut(false);
            order.verify(started.attach()).withStdErr(false);
            order.verify(started.attach()).exec(any());
            order.verify(started.logs()).withFollowStream(true);
            order.verify(started.logs()).withStdOut(true);
            order.verify(started.logs()).withStdErr(true);
            order.verify(started.logs()).exec(any());
        }
    }

    @Test
    void mountsMaterialReadOnlyAndExecutesOnlyFixedMaterialEntryPoint() throws Exception {
        Path material = java.nio.file.Files.createTempDirectory("agent4j-mcp-material");
        java.nio.file.Files.writeString(material.resolve("server.mjs"), "console.log('ready');");
        McpDockerLaunchSpec materialSpec = new McpDockerLaunchSpec(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"), "alpine:3.20", "server.mjs", List.of("--stdio"),
                "/mcp-material", "", "", "/workspace", WorkspaceMountMode.NONE, "none",
                128L * 1024 * 1024, 100_000_000L, 64L, 4096, 8192, 4096, Set.of());
        StartedRunner started = startableRunner(materialSpec);
        try (DockerMcpStdioRunner runner = started.runner()) {
            runner.start(materialSpec, Map.of(), Path.of("D:/agent4j"), material, event -> { });
            var hostConfig = forClass(HostConfig.class);
            verify(started.create()).withHostConfig(hostConfig.capture());
            assertThat(hostConfig.getValue().getBinds()).singleElement().satisfies(bind -> {
                assertThat(bind.getPath()).isEqualTo(material.toRealPath().toString());
                assertThat(bind.getVolume().getPath()).isEqualTo("/mcp-material");
                assertThat(bind.getAccessMode()).isEqualTo(AccessMode.ro);
            });
            var command = forClass(String[].class);
            verify(started.create()).withCmd(command.capture());
            assertThat(command.getValue()).containsExactly("/mcp-material/server.mjs", "--stdio");
        }
    }

    @Test
    void preservesUnreadBytesUntilTheyAreActuallyRead() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        CreateContainerCmd create = mock(CreateContainerCmd.class, RETURNS_SELF);
        StartContainerCmd start = mock(StartContainerCmd.class);
        AttachContainerCmd attach = mock(AttachContainerCmd.class, RETURNS_SELF);
        LogContainerCmd logs = mock(LogContainerCmd.class, RETURNS_SELF);
        StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        CreateContainerResponse response = new CreateContainerResponse();
        response.setId("container-1");
        AtomicReference<ResultCallback<Frame>> callback = new AtomicReference<>();
        when(docker.createContainerCmd("alpine:3.20")).thenReturn(create);
        when(create.exec()).thenReturn(response);
        when(docker.startContainerCmd("container-1")).thenReturn(start);
        when(docker.attachContainerCmd("container-1")).thenReturn(attach);
        when(docker.logContainerCmd("container-1")).thenReturn(logs);
        when(docker.stopContainerCmd("container-1")).thenReturn(stop);
        when(docker.removeContainerCmd("container-1")).thenReturn(remove);
        when(attach.exec(any())).thenAnswer(invocation -> {
            ResultCallback<Frame> value = invocation.getArgument(0);
            value.onStart(() -> { });
            return value;
        });
        when(logs.exec(any())).thenAnswer(invocation -> {
            ResultCallback<Frame> value = invocation.getArgument(0);
            value.onStart(() -> { });
            callback.set(value);
            return value;
        });

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try (DockerMcpStdioRunner runner = new DockerMcpStdioRunner(docker, executor)) {
            var process = runner.start(spec(Set.of()), Map.of(), Path.of("D:/agent4j"), event -> { });
            callback.get().onNext(new Frame(StreamType.STDOUT, new byte[] {'a', 'b', 'c', 'd'}));

            InputStream stdout = process.stdout();
            assertThat(stdout.readNBytes(2)).containsExactly('a', 'b');
            assertTimeoutPreemptively(Duration.ofSeconds(1),
                    () -> assertThat(stdout.readNBytes(2)).containsExactly('c', 'd'));
        }
    }

    @Test
    void configuresHardenedContainerAndFixedManagementLabels() {
        StartedRunner started = startableRunner(spec(Set.of("TOKEN")));
        try (DockerMcpStdioRunner runner = started.runner()) {
            runner.start(spec(Set.of("TOKEN")), Map.of("TOKEN", "value"), Path.of("D:/agent4j"), event -> { });

            var hostConfig = forClass(HostConfig.class);
            @SuppressWarnings("unchecked")
            var labels = forClass(Map.class);
            verify(started.create()).withCmd("/mcp-material/sh", "-c", "printf ready");
            verify(started.create()).withAttachStdin(true);
            verify(started.create()).withAttachStdout(true);
            verify(started.create()).withAttachStderr(true);
            verify(started.create()).withStdinOpen(true);
            verify(started.create()).withTty(false);
            verify(started.create()).withHostConfig(hostConfig.capture());
            verify(started.create()).withLabels(labels.capture());
            verify(started.create()).withEnv(List.of("TOKEN=value"));
            assertThat(hostConfig.getValue().getNetworkMode()).isEqualTo("none");
            assertThat(hostConfig.getValue().getReadonlyRootfs()).isTrue();
            assertThat(hostConfig.getValue().getPrivileged()).isFalse();
            assertThat(hostConfig.getValue().getMemory()).isEqualTo(128L * 1024 * 1024);
            assertThat(hostConfig.getValue().getNanoCPUs()).isEqualTo(100_000_000L);
            assertThat(hostConfig.getValue().getPidsLimit()).isEqualTo(64L);
            assertThat(hostConfig.getValue().getBinds()).singleElement().satisfies(bind -> {
                assertThat(bind.getPath()).isEqualTo(Path.of("D:/agent4j").toString());
                assertThat(bind.getVolume().getPath()).isEqualTo("/mcp-material");
                assertThat(bind.getAccessMode()).isEqualTo(AccessMode.ro);
            });
            assertThat(labels.getValue()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "com.agent.runtime.managed", "true",
                    "com.agent.runtime.kind", "mcp",
                    "com.agent.runtime.installation-id", "00000000-0000-0000-0000-000000000001",
                    "com.agent.runtime.snapshot-id", "00000000-0000-0000-0000-000000000002"));
        }
    }

    @Test
    void mountsWorkspaceReadOnlyAndReadWriteAsConfigured() {
        assertWorkspaceMount(WorkspaceMountMode.READ_ONLY, AccessMode.ro);
        assertWorkspaceMount(WorkspaceMountMode.READ_WRITE, AccessMode.rw);
    }

    @Test
    void separatesStreamsAndForwardsStdinToDockerAttachInput() throws Exception {
        StartedRunner started = startableRunner(spec(Set.of()));
        try (DockerMcpStdioRunner runner = started.runner()) {
            var process = runner.start(spec(Set.of()), Map.of(), Path.of("D:/agent4j"), event -> { });
            started.callback().get().onNext(new Frame(StreamType.STDOUT, new byte[] {'o'}));
            started.callback().get().onNext(new Frame(StreamType.STDERR, new byte[] {'e'}));
            process.stdin().write(new byte[] {'i'});
            process.stdin().flush();

            assertThat(process.stdout().read()).isEqualTo((int) 'o');
            assertThat(process.stderr().read()).isEqualTo((int) 'e');
            assertThat(started.attachInput().get().read()).isEqualTo((int) 'i');
        }
    }

    @Test
    void notifiesOnceAndClosesStreamsWhenStdoutFrameExceedsLimit() throws Exception {
        McpDockerLaunchSpec limited = spec(Set.of(), 2, 8, 8);
        StartedRunner started = startableRunner(limited);
        CountDownLatch notified = new CountDownLatch(1);
        AtomicReference<McpRuntimeFailureListener.Event> event = new AtomicReference<>();
        try (DockerMcpStdioRunner runner = started.runner()) {
            var process = runner.start(limited, Map.of(), Path.of("D:/agent4j"), value -> {
                event.set(value);
                notified.countDown();
            });
            started.callback().get().onNext(new Frame(StreamType.STDOUT, new byte[] {'x', 'y', 'z'}));
            started.callback().get().onNext(new Frame(StreamType.STDOUT, new byte[] {'q', 'r', 's'}));

            assertThat(notified.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(event.get()).extracting(
                    McpRuntimeFailureListener.Event::installationId,
                    McpRuntimeFailureListener.Event::snapshotId,
                    McpRuntimeFailureListener.Event::containerId,
                    McpRuntimeFailureListener.Event::reason)
                    .containsExactly(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                            UUID.fromString("00000000-0000-0000-0000-000000000002"), "container-1",
                            McpRuntimeFailureListener.Reason.STDOUT_FRAME_LIMIT_EXCEEDED);
            assertThat(event.get().cause()).isInstanceOf(IOException.class)
                    .hasMessage("MCP stdout frame 超过上限");
            assertThat(process.stdout().read()).isEqualTo(-1);
            assertThat(notified.getCount()).isZero();
        }
    }

    @Test
    void enforcesUnreadStdoutAndCumulativeStderrLimitsIndependently() throws Exception {
        McpDockerLaunchSpec stdoutLimited = spec(Set.of(), 8, 3, 8);
        StartedRunner stdoutStarted = startableRunner(stdoutLimited);
        CountDownLatch stdoutNotified = new CountDownLatch(1);
        AtomicReference<McpRuntimeFailureListener.Event> stdoutEvent = new AtomicReference<>();
        try (DockerMcpStdioRunner runner = stdoutStarted.runner()) {
            var process = runner.start(stdoutLimited, Map.of(), Path.of("D:/agent4j"), event -> {
                stdoutEvent.set(event);
                stdoutNotified.countDown();
            });
            stdoutStarted.callback().get().onNext(new Frame(StreamType.STDOUT, new byte[] {'a', 'b', 'c'}));
            stdoutStarted.callback().get().onNext(new Frame(StreamType.STDOUT, new byte[] {'d'}));
            assertThat(stdoutNotified.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(stdoutEvent.get()).extracting(
                    McpRuntimeFailureListener.Event::installationId,
                    McpRuntimeFailureListener.Event::snapshotId,
                    McpRuntimeFailureListener.Event::containerId,
                    McpRuntimeFailureListener.Event::reason)
                    .containsExactly(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                            UUID.fromString("00000000-0000-0000-0000-000000000002"), "container-1",
                            McpRuntimeFailureListener.Reason.STDOUT_BUFFER_LIMIT_EXCEEDED);
            assertThat(stdoutEvent.get().cause()).isInstanceOf(IOException.class)
                    .hasMessage("MCP stdout 缓冲超过上限");
            assertThat(process.stdout().read()).isEqualTo(-1);
        }

        McpDockerLaunchSpec stderrLimited = spec(Set.of(), 8, 8, 3);
        StartedRunner stderrStarted = startableRunner(stderrLimited);
        CountDownLatch stderrNotified = new CountDownLatch(1);
        AtomicReference<McpRuntimeFailureListener.Event> stderrEvent = new AtomicReference<>();
        try (DockerMcpStdioRunner runner = stderrStarted.runner()) {
            var process = runner.start(stderrLimited, Map.of(), Path.of("D:/agent4j"), value -> {
                stderrEvent.set(value);
                stderrNotified.countDown();
            });
            stderrStarted.callback().get().onNext(new Frame(StreamType.STDERR, new byte[] {'a', 'b'}));
            assertThat(process.stderr().readNBytes(2)).containsExactly('a', 'b');
            stderrStarted.callback().get().onNext(new Frame(StreamType.STDERR, new byte[] {'c', 'd'}));
            assertThat(stderrNotified.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(stderrEvent.get()).extracting(
                    McpRuntimeFailureListener.Event::installationId,
                    McpRuntimeFailureListener.Event::snapshotId,
                    McpRuntimeFailureListener.Event::containerId,
                    McpRuntimeFailureListener.Event::reason)
                    .containsExactly(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                            UUID.fromString("00000000-0000-0000-0000-000000000002"), "container-1",
                            McpRuntimeFailureListener.Reason.STDERR_LIMIT_EXCEEDED);
            assertThat(stderrEvent.get().cause()).isInstanceOf(IOException.class)
                    .hasMessage("MCP stderr 超过上限");
            assertThat(process.stderr().read()).isEqualTo(-1);
        }
    }

    @Test
    void destroyIsIdempotentAndUnblocksPendingOutputRead() throws Exception {
        StartedRunner started = startableRunner(spec(Set.of()));
        try (DockerMcpStdioRunner runner = started.runner()) {
            var process = runner.start(spec(Set.of()), Map.of(), Path.of("D:/agent4j"), event -> { });
            CompletableFuture<Integer> result = CompletableFuture.supplyAsync(() -> {
                try { return process.stdout().read(); }
                catch (Exception exception) { throw new IllegalStateException(exception); }
            }, Executors.newVirtualThreadPerTaskExecutor());
            process.destroy();
            process.destroy();

            assertThat(result.get(2, TimeUnit.SECONDS)).isEqualTo(-1);
            assertThat(process.isAlive()).isFalse();
            verify(started.stop(), times(1)).exec();
            verify(started.remove(), times(1)).exec();
        }
    }

    @Test
    void errorCallbackNotifiesOnceWithoutBlockingDockerCallbackThread() throws Exception {
        StartedRunner started = startableRunner(spec(Set.of()));
        CountDownLatch listenerStarted = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        CountDownLatch listenerFinished = new CountDownLatch(1);
        AtomicReference<McpRuntimeFailureListener.Event> event = new AtomicReference<>();
        try (DockerMcpStdioRunner runner = started.runner()) {
            runner.start(spec(Set.of()), Map.of(), Path.of("D:/agent4j"), value -> {
                event.set(value);
                listenerStarted.countDown();
                try { releaseListener.await(); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                finally { listenerFinished.countDown(); }
            });
            assertTimeoutPreemptively(Duration.ofSeconds(1),
                    () -> started.callback().get().onError(new IOException("log closed")));
            started.callback().get().onError(new IOException("duplicate"));
            assertThat(listenerStarted.await(2, TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(event.get().reason()).isEqualTo(McpRuntimeFailureListener.Reason.ATTACH_DISCONNECTED);
            } finally {
                releaseListener.countDown();
            }
            assertThat(listenerFinished.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void stdinAttachErrorStopsContainerButLetsLogReplayPerformFinalNotification() throws Exception {
        StartedRunner started = startableRunner(spec(Set.of()));
        AtomicInteger notifications = new AtomicInteger();
        CountDownLatch stopExecuted = new CountDownLatch(1);
        doAnswer(invocation -> {
            stopExecuted.countDown();
            return null;
        }).when(started.stop()).exec();
        try (DockerMcpStdioRunner runner = started.runner()) {
            var process = runner.start(spec(Set.of()), Map.of(), Path.of("D:/agent4j"), event -> notifications.incrementAndGet());
            assertTimeoutPreemptively(Duration.ofSeconds(1),
                    () -> started.inputCallback().get().onError(new IOException("stdin attach closed")));
            assertThatThrownBy(() -> process.stdin().write('x')).isInstanceOf(IOException.class);
            assertThat(notifications).hasValue(0);
            assertThat(stopExecuted.await(2, TimeUnit.SECONDS)).isTrue();
            verify(started.stop(), times(1)).exec();
            started.callback().get().onNext(new Frame(StreamType.STDOUT, new byte[] {'o', 'k'}));
            started.callback().get().onComplete();
            assertThat(process.stdout().readAllBytes()).containsExactly('o', 'k');
            assertThat(notifications).hasValue(1);
        }
    }

    @Test
    void failureListenerCanCloseRunnerAfterCleanupWithoutDeadlock() throws Exception {
        StartedRunner started = startableRunner(spec(Set.of()));
        AtomicReference<DockerMcpStdioRunner> runnerRef = new AtomicReference<>();
        CountDownLatch closed = new CountDownLatch(1);
        AtomicInteger notifications = new AtomicInteger();
        DockerMcpStdioRunner runner = started.runner();
        runnerRef.set(runner);
        runner.start(spec(Set.of()), Map.of(), Path.of("D:/agent4j"), event -> {
            notifications.incrementAndGet();
            runnerRef.get().close();
            closed.countDown();
        });
        started.callback().get().onError(new IOException("attach closed"));
        assertThat(closed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(notifications).hasValue(1);
    }

    @Test
    void externalCloseWaitsForBlockedFailureListener() throws Exception {
        StartedRunner started = startableRunner(spec(Set.of()));
        CountDownLatch listenerStarted = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        DockerMcpStdioRunner runner = started.runner();
        runner.start(spec(Set.of()), Map.of(), Path.of("D:/agent4j"), event -> {
            listenerStarted.countDown();
            try { releaseListener.await(); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        });
        started.callback().get().onError(new IOException("log closed"));
        assertThat(listenerStarted.await(2, TimeUnit.SECONDS)).isTrue();
        ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var close = callers.submit(() -> {
                runner.close();
                return null;
            });
            Thread.sleep(100);
            assertThat(close.isDone()).isFalse();
            releaseListener.countDown();
            assertThat(close.get(2, TimeUnit.SECONDS)).isNull();
        } finally {
            releaseListener.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void rejectsStartAfterCloseWithoutCreatingContainer() {
        StartedRunner started = startableRunner(spec(Set.of()));
        DockerMcpStdioRunner runner = started.runner();
        runner.close();
        assertThatThrownBy(() -> runner.start(spec(Set.of()), Map.of(), Path.of("D:/agent4j"), event -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Docker MCP runner 已关闭");
        verify(started.create(), times(0)).exec();
    }

    @Test
    void concurrentCloseWaitsForStartThenCleansUpCreatedContainer() throws Exception {
        StartedRunner started = startableRunner(spec(Set.of()));
        CountDownLatch createEntered = new CountDownLatch(1);
        CountDownLatch releaseCreate = new CountDownLatch(1);
        when(started.create().exec()).thenAnswer(invocation -> {
            createEntered.countDown();
            assertThat(releaseCreate.await(2, TimeUnit.SECONDS)).isTrue();
            CreateContainerResponse response = new CreateContainerResponse();
            response.setId("container-1");
            return response;
        });
        ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            DockerMcpStdioRunner runner = started.runner();
            var startResult = callers.submit(() -> {
                try {
                    runner.start(spec(Set.of()), Map.of(), Path.of("D:/agent4j"), event -> { });
                    return null;
                } catch (RuntimeException exception) {
                    return exception;
                }
            });
            assertThat(createEntered.await(2, TimeUnit.SECONDS)).isTrue();
            var closeResult = callers.submit(() -> {
                runner.close();
                return null;
            });
            releaseCreate.countDown();
            assertThat(startResult.get(2, TimeUnit.SECONDS)).isNull();
            assertThat(closeResult.get(2, TimeUnit.SECONDS)).isNull();
            verify(started.stop(), times(1)).exec();
            verify(started.remove(), times(1)).exec();
            assertThatThrownBy(() -> runner.start(spec(Set.of()), Map.of(), Path.of("D:/agent4j"), event -> { }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Docker MCP runner 已关闭");
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void completionClassifiesStoppedContainerAsExitedOutsideDockerCallbackThread() throws Exception {
        StartedRunner started = startableRunner(spec(Set.of()));
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
        when(started.docker().inspectContainerCmd("container-1")).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);
        when(response.getState()).thenReturn(state);
        when(state.getRunning()).thenReturn(false);
        CountDownLatch notified = new CountDownLatch(1);
        AtomicReference<McpRuntimeFailureListener.Event> event = new AtomicReference<>();
        try (DockerMcpStdioRunner runner = started.runner()) {
            runner.start(spec(Set.of()), Map.of(), Path.of("D:/agent4j"), value -> {
                event.set(value);
                notified.countDown();
            });
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> started.callback().get().onComplete());
            assertThat(notified.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(event.get().reason()).isEqualTo(McpRuntimeFailureListener.Reason.CONTAINER_EXITED);
            verify(inspect).exec();
        }
    }

    @Test
    void completionClassifiesRunningContainerAsAttachDisconnectBeforeCleanup() throws Exception {
        StartedRunner started = startableRunner(spec(Set.of()));
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
        when(started.docker().inspectContainerCmd("container-1")).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);
        when(response.getState()).thenReturn(state);
        when(state.getRunning()).thenReturn(true);
        CountDownLatch notified = new CountDownLatch(1);
        AtomicReference<McpRuntimeFailureListener.Event> event = new AtomicReference<>();
        try (DockerMcpStdioRunner runner = started.runner()) {
            runner.start(spec(Set.of()), Map.of(), Path.of("D:/agent4j"), value -> {
                event.set(value);
                notified.countDown();
            });
            started.callback().get().onComplete();
            assertThat(notified.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(event.get().reason()).isEqualTo(McpRuntimeFailureListener.Reason.ATTACH_DISCONNECTED);
            var order = inOrder(inspect, started.stop());
            order.verify(inspect).exec();
            order.verify(started.stop()).exec();
        }
    }

    @Test
    void keepsLogReplayAuthoritativeAfterStdinAttachError() throws Exception {
        StartedRunner started = startableRunner(spec(Set.of()));
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
        when(started.docker().inspectContainerCmd("container-1")).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);
        when(response.getState()).thenReturn(state);
        when(state.getRunning()).thenReturn(false);
        CountDownLatch notified = new CountDownLatch(1);
        AtomicReference<McpRuntimeFailureListener.Event> event = new AtomicReference<>();
        try (DockerMcpStdioRunner runner = started.runner()) {
            var process = runner.start(spec(Set.of()), Map.of(), Path.of("D:/agent4j"), value -> {
                event.set(value);
                notified.countDown();
            });
            started.inputCallback().get().onError(new IOException("stdin attach closed"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (mockingDetails(started.stop()).getInvocations().size() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            verify(started.stop(), times(1)).exec();
            started.callback().get().onNext(new Frame(StreamType.STDOUT, new byte[] {'o', 'k'}));
            started.callback().get().onComplete();

            assertThat(notified.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(event.get().reason()).isEqualTo(McpRuntimeFailureListener.Reason.ATTACH_DISCONNECTED);
            assertThat(process.stdout().readAllBytes()).containsExactly('o', 'k');
        }
    }

    private static void assertWorkspaceMount(WorkspaceMountMode mode, AccessMode accessMode) {
        McpDockerLaunchSpec mounted = mountedSpec(mode);
        StartedRunner started = startableRunner(mounted);
        try (DockerMcpStdioRunner runner = started.runner()) {
            runner.start(mounted, Map.of(), Path.of("D:/agent4j"), event -> { });
            var hostConfig = forClass(HostConfig.class);
            verify(started.create()).withHostConfig(hostConfig.capture());
            assertThat(hostConfig.getValue().getBinds()).hasSize(2);
            assertThat(hostConfig.getValue().getBinds()[0]).satisfies(bind -> {
                assertThat(bind.getPath()).isEqualTo(Path.of("D:/agent4j").toString());
                assertThat(bind.getVolume().getPath()).isEqualTo("/mcp-material");
                assertThat(bind.getAccessMode()).isEqualTo(AccessMode.ro);
            });
            assertThat(hostConfig.getValue().getBinds()[1]).satisfies(bind -> {
                assertThat(bind.getPath()).isEqualTo(Path.of("D:/agent4j").toString());
                assertThat(bind.getVolume().getPath()).isEqualTo("/workspace");
                assertThat(bind.getAccessMode()).isEqualTo(accessMode);
            });
        }
    }

    private static McpDockerLaunchSpec mountedSpec(WorkspaceMountMode mode) {
        return new McpDockerLaunchSpec(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"), "alpine:3.20", "sh",
                List.of("-c", "printf ready"), "/workspace", mode, "none",
                128L * 1024 * 1024, 100_000_000L, 64L, 4096, 8192, 4096, Set.of());
    }

    private static StartedRunner startableRunner(McpDockerLaunchSpec ignored) {
        DockerClient docker = mock(DockerClient.class);
        CreateContainerCmd create = mock(CreateContainerCmd.class, RETURNS_SELF);
        StartContainerCmd start = mock(StartContainerCmd.class);
        AttachContainerCmd attach = mock(AttachContainerCmd.class, RETURNS_SELF);
        LogContainerCmd logs = mock(LogContainerCmd.class, RETURNS_SELF);
        StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        CreateContainerResponse response = new CreateContainerResponse();
        response.setId("container-1");
        AtomicReference<ResultCallback<Frame>> callback = new AtomicReference<>();
        AtomicReference<ResultCallback<Frame>> inputCallback = new AtomicReference<>();
        AtomicReference<InputStream> attachInput = new AtomicReference<>();
        when(docker.createContainerCmd("alpine:3.20")).thenReturn(create);
        when(create.exec()).thenReturn(response);
        when(docker.startContainerCmd("container-1")).thenReturn(start);
        when(docker.attachContainerCmd("container-1")).thenReturn(attach);
        when(docker.logContainerCmd("container-1")).thenReturn(logs);
        when(docker.stopContainerCmd("container-1")).thenReturn(stop);
        when(docker.removeContainerCmd("container-1")).thenReturn(remove);
        when(attach.withStdIn(any())).thenAnswer(invocation -> {
            attachInput.set(invocation.getArgument(0));
            return attach;
        });
        when(attach.exec(any())).thenAnswer(invocation -> {
            ResultCallback<Frame> value = invocation.getArgument(0);
            value.onStart(() -> { });
            inputCallback.set(value);
            return value;
        });
        when(logs.exec(any())).thenAnswer(invocation -> {
            ResultCallback<Frame> value = invocation.getArgument(0);
            value.onStart(() -> { });
            callback.set(value);
            return value;
        });
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        return new StartedRunner(docker, new DockerMcpStdioRunner(docker, executor), create, start, attach, logs, stop, remove, callback, inputCallback, attachInput);
    }

    private record StartedRunner(
            DockerClient docker,
            DockerMcpStdioRunner runner,
            CreateContainerCmd create,
            StartContainerCmd start,
            AttachContainerCmd attach,
            LogContainerCmd logs,
            StopContainerCmd stop,
            RemoveContainerCmd remove,
            AtomicReference<ResultCallback<Frame>> callback,
            AtomicReference<ResultCallback<Frame>> inputCallback,
            AtomicReference<InputStream> attachInput) { }

    private static McpDockerLaunchSpec spec(Set<String> environmentNames) {
        return spec(environmentNames, 4096, 8192, 4096);
    }

    private static McpDockerLaunchSpec spec(
            Set<String> environmentNames, int maxStdoutFrameBytes, int maxStdoutBufferedBytes, int maxStderrBytes) {
        return new McpDockerLaunchSpec(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"), "alpine:3.20", "sh",
                List.of("-c", "printf ready"), "/workspace", WorkspaceMountMode.NONE, "none",
                128L * 1024 * 1024, 100_000_000L, 64L,
                maxStdoutFrameBytes, maxStdoutBufferedBytes, maxStderrBytes, environmentNames);
    }
}
