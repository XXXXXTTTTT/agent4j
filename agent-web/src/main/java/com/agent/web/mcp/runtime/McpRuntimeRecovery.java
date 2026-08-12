package com.agent.web.mcp.runtime;

import com.agent.web.mcp.installation.McpInstallationAggregate;
import com.agent.web.mcp.installation.McpInstallationRepository;
import com.agent.web.mcp.installation.McpInstallationStatus;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** 在应用就绪后仅经 DockerMcpStdioRunner 恢复中断的 MCP 生命周期。 */
public final class McpRuntimeRecovery implements ApplicationListener<ApplicationReadyEvent>, AutoCloseable {
    private final McpInstallationRepository repository;
    private final DockerMcpStdioRunner runner;
    private final McpInstallationRuntime runtime;
    private final AtomicBoolean recovered = new AtomicBoolean();

    public McpRuntimeRecovery(McpInstallationRepository repository, DockerMcpStdioRunner runner,
                              McpInstallationRuntime runtime) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.runner = Objects.requireNonNull(runner, "runner 不能为空");
        this.runtime = Objects.requireNonNull(runtime, "runtime 不能为空");
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        recover();
    }

    /** 幂等执行一次恢复扫描。 */
    public void recover() {
        if (!recovered.compareAndSet(false, true)) return;
        java.util.Map<ContainerKey, List<DockerMcpContainer>> containers = runner.findManagedContainers().stream()
                .collect(java.util.stream.Collectors.groupingBy(value -> new ContainerKey(value.installationId(), value.snapshotId())));
        for (McpInstallationAggregate aggregate : repository.findRecoverableInstallations()) {
            List<DockerMcpContainer> matching = containers.getOrDefault(new ContainerKey(aggregate.installation().installationId(),
                    aggregate.installation().snapshotId()), List.of());
            if (aggregate.installation().status() == McpInstallationStatus.RUNNING) {
                runtime.recoverRunning(aggregate, matching);
            } else if (aggregate.installation().status() == McpInstallationStatus.STOPPING) {
                runtime.recoverStopping(aggregate, matching);
            } else if (aggregate.installation().status() == McpInstallationStatus.INSTALLING) {
                runtime.recoverInstalling(aggregate, matching);
            }
        }
    }

    /** Spring 正常关闭期间不触发失败收敛。 */
    @Override
    public void close() {
        runtime.closeNormally();
    }

    /** 受管容器只可由同一安装及固定快照共同标识。 */
    private record ContainerKey(UUID installationId, UUID snapshotId) { }
}
