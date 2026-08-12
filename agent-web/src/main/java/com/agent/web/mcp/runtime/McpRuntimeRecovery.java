package com.agent.web.mcp.runtime;

import com.agent.web.mcp.installation.McpInstallationAggregate;
import com.agent.web.mcp.installation.McpInstallationRepository;
import com.agent.web.mcp.installation.McpInstallationStatus;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import java.util.List;
import java.util.Map;
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
        Map<UUID, DockerMcpContainer> containers = runner.findManagedContainers().stream()
                .collect(java.util.stream.Collectors.toMap(DockerMcpContainer::installationId, value -> value,
                        (first, ignored) -> first));
        for (McpInstallationAggregate aggregate : repository.findRecoverableInstallations()) {
            DockerMcpContainer container = containers.get(aggregate.installation().installationId());
            if (aggregate.installation().status() == McpInstallationStatus.RUNNING) {
                runtime.recoverRunning(aggregate, container);
            } else if (aggregate.installation().status() == McpInstallationStatus.STOPPING) {
                runtime.recoverStopping(aggregate, container);
            } else if (aggregate.installation().status() == McpInstallationStatus.INSTALLING) {
                runtime.recoverInstalling(aggregate, container);
            }
        }
    }

    /** Spring 正常关闭期间不触发失败收敛。 */
    @Override
    public void close() {
        runtime.closeNormally();
    }
}
