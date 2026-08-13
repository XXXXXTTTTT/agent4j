package com.agent.web.mcp.runtime;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DockerMcpStdioProcessTest {
    @Test
    void failureCanBeClaimedOnlyOnce() {
        DockerMcpStdioProcess process = new DockerMcpStdioProcess(
                new ByteArrayInputStream(new byte[0]), new java.io.ByteArrayOutputStream(),
                new ByteArrayInputStream(new byte[0]), () -> { });

        assertThat(process.claimFailure()).isTrue();
        assertThat(process.claimFailure()).isFalse();
        process.destroy();

        assertThat(process.isAlive()).isFalse();
    }

    @Test
    void normalDestroyRunsCleanupOnceWithoutFailureNotification() {
        AtomicInteger destroyed = new AtomicInteger();
        DockerMcpStdioProcess process = new DockerMcpStdioProcess(
                new ByteArrayInputStream(new byte[0]), new java.io.ByteArrayOutputStream(),
                new ByteArrayInputStream(new byte[0]), destroyed::incrementAndGet);

        process.destroy();
        process.destroy();

        assertThat(destroyed).hasValue(1);
    }

    @Test
    void recoveryPreparationRedirectsTheNextDestroyToLocalDetach() {
        AtomicInteger destroyed = new AtomicInteger();
        AtomicInteger detached = new AtomicInteger();
        DockerMcpStdioProcess process = new DockerMcpStdioProcess(
                new ByteArrayInputStream(new byte[0]), new java.io.ByteArrayOutputStream(),
                new ByteArrayInputStream(new byte[0]), "container-1", destroyed::incrementAndGet, detached::incrementAndGet);

        process.prepareForRecovery();
        process.destroy();

        assertThat(detached).hasValue(1);
        assertThat(destroyed).hasValue(0);
        assertThat(process.isAlive()).isFalse();
    }
}
