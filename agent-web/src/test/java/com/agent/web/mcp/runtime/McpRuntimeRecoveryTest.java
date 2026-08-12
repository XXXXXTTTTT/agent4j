package com.agent.web.mcp.runtime;

import com.agent.web.mcp.installation.McpInstallationAggregate;
import com.agent.web.mcp.installation.McpInstallationRecord;
import com.agent.web.mcp.installation.McpInstallationRepository;
import com.agent.web.mcp.installation.McpInstallationStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class McpRuntimeRecoveryTest {

    @Test
    void ignoresRepeatedApplicationReadyEventsAfterFirstRecovery() {
        McpInstallationRepository repository = mock(McpInstallationRepository.class);
        DockerMcpStdioRunner runner = mock(DockerMcpStdioRunner.class);
        McpInstallationRuntime runtime = mock(McpInstallationRuntime.class);
        when(runner.findManagedContainers()).thenReturn(List.of());
        when(repository.findRecoverableInstallations()).thenReturn(List.of());
        McpRuntimeRecovery recovery = new McpRuntimeRecovery(repository, runner, runtime);

        recovery.recover();
        recovery.recover();

        verifyNoInteractions(runtime);
        org.mockito.Mockito.verify(runner).findManagedContainers();
        org.mockito.Mockito.verify(repository).findRecoverableInstallations();
    }

    @Test
    void recoversEveryPersistedLifecycleStateOnlyThroughRuntimePort() {
        McpInstallationRepository repository = mock(McpInstallationRepository.class);
        DockerMcpStdioRunner runner = mock(DockerMcpStdioRunner.class);
        McpInstallationRuntime runtime = mock(McpInstallationRuntime.class);
        McpInstallationAggregate running = aggregate(McpInstallationStatus.RUNNING,
                UUID.fromString("2894522d-7b7c-4c91-a7f8-8213ded5c2a3"));
        McpInstallationAggregate missing = aggregate(McpInstallationStatus.RUNNING,
                UUID.fromString("9188f8b0-e5a4-474d-8b6e-817b95539917"));
        McpInstallationAggregate installing = aggregate(McpInstallationStatus.INSTALLING,
                UUID.fromString("995ce904-d062-40ef-9c6d-0eb0d04e9ee6"));
        McpInstallationAggregate stopping = aggregate(McpInstallationStatus.STOPPING,
                UUID.fromString("53b155df-c6c2-4c20-8cce-1f13c405da6e"));
        DockerMcpContainer container = new DockerMcpContainer("container-running",
                running.installation().installationId(), running.installation().snapshotId(), true);
        DockerMcpContainer stoppingContainer = new DockerMcpContainer("container-stopping",
                stopping.installation().installationId(), stopping.installation().snapshotId(), true);
        when(runner.findManagedContainers()).thenReturn(List.of(container, stoppingContainer));
        when(repository.findRecoverableInstallations()).thenReturn(List.of(running, missing, installing, stopping));

        new McpRuntimeRecovery(repository, runner, runtime).recover();

        verify(runtime).recoverRunning(running, List.of(container));
        verify(runtime).recoverRunning(missing, List.of());
        verify(runtime).recoverInstalling(installing, List.of());
        verify(runtime).recoverStopping(stopping, List.of(stoppingContainer));
    }

    @Test
    void doesNotAttachContainerWithSameInstallationButDifferentSnapshot() {
        McpInstallationRepository repository = mock(McpInstallationRepository.class);
        DockerMcpStdioRunner runner = mock(DockerMcpStdioRunner.class);
        McpInstallationRuntime runtime = mock(McpInstallationRuntime.class);
        McpInstallationAggregate running = aggregate(McpInstallationStatus.RUNNING,
                UUID.fromString("2894522d-7b7c-4c91-a7f8-8213ded5c2a3"));
        DockerMcpContainer staleSnapshot = new DockerMcpContainer("container-stale",
                running.installation().installationId(), UUID.fromString("e8b9dfe7-1147-4dd1-93cb-e09e6e15d5c5"), true);
        when(runner.findManagedContainers()).thenReturn(List.of(staleSnapshot));
        when(repository.findRecoverableInstallations()).thenReturn(List.of(running));

        new McpRuntimeRecovery(repository, runner, runtime).recover();

        verify(runtime).recoverRunning(running, List.of());
    }

    @Test
    void retainsEverySameTupleContainerForRuntimeToConverge() {
        McpInstallationRepository repository = mock(McpInstallationRepository.class);
        DockerMcpStdioRunner runner = mock(DockerMcpStdioRunner.class);
        McpInstallationRuntime runtime = mock(McpInstallationRuntime.class);
        McpInstallationAggregate running = aggregate(McpInstallationStatus.RUNNING,
                UUID.fromString("2894522d-7b7c-4c91-a7f8-8213ded5c2a3"));
        DockerMcpContainer first = new DockerMcpContainer("container-first", running.installation().installationId(),
                running.installation().snapshotId(), true);
        DockerMcpContainer second = new DockerMcpContainer("container-second", running.installation().installationId(),
                running.installation().snapshotId(), true);
        when(runner.findManagedContainers()).thenReturn(List.of(first, second));
        when(repository.findRecoverableInstallations()).thenReturn(List.of(running));

        new McpRuntimeRecovery(repository, runner, runtime).recover();

        verify(runtime).recoverRunning(running, List.of(first, second));
    }

    @Test
    void normalApplicationCloseDoesNotRecordFailure() {
        McpInstallationRepository repository = mock(McpInstallationRepository.class);
        DockerMcpStdioRunner runner = mock(DockerMcpStdioRunner.class);
        McpInstallationRuntime runtime = mock(McpInstallationRuntime.class);
        McpRuntimeRecovery recovery = new McpRuntimeRecovery(repository, runner, runtime);

        recovery.close();

        verify(runtime).closeNormally();
        verifyNoInteractions(repository, runner);
    }

    private static McpInstallationAggregate aggregate(McpInstallationStatus status, UUID installationId) {
        McpInstallationAggregate aggregate = mock(McpInstallationAggregate.class);
        McpInstallationRecord installation = mock(McpInstallationRecord.class);
        UUID snapshotId = UUID.fromString("b976766f-0456-4bbe-b258-d4afcd0ad2c6");
        when(installation.installationId()).thenReturn(installationId);
        when(installation.snapshotId()).thenReturn(snapshotId);
        when(installation.status()).thenReturn(status);
        when(aggregate.installation()).thenReturn(installation);
        return aggregate;
    }
}
