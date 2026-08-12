package com.agent.web.mcp.runtime;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DockerMcpStdioProcessTest {
    @Test
    void failureIsClaimedOnceWhenListenerReenters() {
        AtomicInteger failureCalls = new AtomicInteger();
        AtomicInteger destroyed = new AtomicInteger();
        DockerMcpStdioProcess[] holder = new DockerMcpStdioProcess[1];
        DockerMcpStdioProcess process = new DockerMcpStdioProcess(
                new ByteArrayInputStream(new byte[0]), new java.io.ByteArrayOutputStream(),
                new ByteArrayInputStream(new byte[0]), destroyed::incrementAndGet,
                (reason, failure) -> {
                    failureCalls.incrementAndGet();
                    holder[0].fail(McpRuntimeFailureListener.Reason.STREAM_IO_FAILED,
                            new IllegalStateException("重复失败"));
                });
        holder[0] = process;

        process.fail(McpRuntimeFailureListener.Reason.ATTACH_DISCONNECTED,
                new RuntimeException("attach disconnected"));
        process.destroy();

        assertThat(process.isAlive()).isFalse();
        assertThat(failureCalls).hasValue(1);
        assertThat(destroyed).hasValue(0);
    }

    @Test
    void normalDestroyRunsCleanupOnceWithoutFailureNotification() {
        AtomicInteger failureCalls = new AtomicInteger();
        AtomicInteger destroyed = new AtomicInteger();
        DockerMcpStdioProcess process = new DockerMcpStdioProcess(
                new ByteArrayInputStream(new byte[0]), new java.io.ByteArrayOutputStream(),
                new ByteArrayInputStream(new byte[0]), destroyed::incrementAndGet,
                (reason, failure) -> failureCalls.incrementAndGet());

        process.destroy();
        process.destroy();

        assertThat(failureCalls).hasValue(0);
        assertThat(destroyed).hasValue(1);
    }
}
