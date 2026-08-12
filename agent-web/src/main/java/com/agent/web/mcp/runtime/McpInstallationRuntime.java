package com.agent.web.mcp.runtime;

import com.agent.core.tool.ToolRegistry;
import com.agent.core.tool.mcp.McpClient;
import com.agent.core.tool.mcp.McpStdioTransport;
import com.agent.core.tool.mcp.McpToolRegistryAdapter;
import com.agent.web.capability.CapabilityManagementAuditEvent;
import com.agent.web.capability.InstallationScope;
import com.agent.web.identity.Actor;
import com.agent.web.identity.ActorResolver;
import com.agent.web.mcp.installation.McpInstallationAggregate;
import com.agent.web.mcp.installation.McpInstallationConflictException;
import com.agent.web.mcp.installation.McpInstallationRecord;
import com.agent.web.mcp.installation.McpInstallationRepository;
import com.agent.web.mcp.installation.McpInstallationStatus;
import com.agent.web.mcp.installation.McpRuntimeFailureCompletion;
import com.agent.web.mcp.installation.McpRuntimeStartCompletion;
import com.agent.web.mcp.installation.McpRuntimeStopCompletion;
import com.agent.web.mcp.installation.McpToolBindingRecord;
import com.agent.web.workspace.WorkspaceAccessService;
import com.agent.web.workspace.WorkspacePermission;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 在工作区权限和安装快照边界内启停 MCP Docker 运行时。 */
public final class McpInstallationRuntime implements AutoCloseable {
    private final ActorResolver actorResolver;
    private final WorkspaceAccessService workspaceAccess;
    private final McpInstallationRepository repository;
    private final McpRuntimeMaterialProvider materialProvider;
    private final McpRuntimeSecretProvider secretProvider;
    private final DockerMcpStdioRunner runner;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final McpRuntimeConfiguration configuration;
    private final Clock clock;
    private final Map<UUID, ActiveRuntime> active = new ConcurrentHashMap<>();
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();
    private volatile boolean closing;

    public McpInstallationRuntime(
            ActorResolver actorResolver,
            WorkspaceAccessService workspaceAccess,
            McpInstallationRepository repository,
            McpRuntimeMaterialProvider materialProvider,
            McpRuntimeSecretProvider secretProvider,
            DockerMcpStdioRunner runner,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            McpRuntimeConfiguration configuration,
            Clock clock) {
        this.actorResolver = Objects.requireNonNull(actorResolver, "actorResolver 不能为空");
        this.workspaceAccess = Objects.requireNonNull(workspaceAccess, "workspaceAccess 不能为空");
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.materialProvider = Objects.requireNonNull(materialProvider, "materialProvider 不能为空");
        this.secretProvider = Objects.requireNonNull(secretProvider, "secretProvider 不能为空");
        this.runner = Objects.requireNonNull(runner, "runner 不能为空");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.configuration = Objects.requireNonNull(configuration, "configuration 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /** 显式启动一条安装记录；环境变量值只在本次调用期间存在。 */
    public McpInstallationRecord start(UUID requestWorkspaceId, UUID installationId, LifecycleRequest request) {
        Actor actor = actorResolver.current();
        return within(installationId, () -> startInternal(actor, requestWorkspaceId, installationId, request));
    }

    /** 显式停止一条运行中的安装记录。 */
    public McpInstallationRecord stop(UUID requestWorkspaceId, UUID installationId, LifecycleRequest request) {
        Actor actor = actorResolver.current();
        return within(installationId, () -> stopInternal(actor, requestWorkspaceId, installationId, request));
    }

    /** 应用重启后接管已处于 RUNNING 的受管容器。 */
    public void recoverRunning(McpInstallationAggregate aggregate, DockerMcpContainer container) {
        Objects.requireNonNull(aggregate, "aggregate 不能为空");
        Objects.requireNonNull(container, "container 不能为空");
        McpInstallationRecord installation = aggregate.installation();
        within(installation.installationId(), () -> {
            if (installation.status() != McpInstallationStatus.RUNNING
                    || !installation.snapshotId().equals(container.snapshotId())
                    || !installation.installationId().equals(container.installationId())
                    || installation.runtimeWorkspaceId() == null || installation.containerId() == null
                    || !installation.containerId().equals(container.containerId()) || !container.running()) {
                failSynchronously(installation, aggregate, installation.runtimeWorkspaceId(), "RECOVERY_CONTAINER_MISMATCH");
                return null;
            }
            try {
                if (installation.scope() == InstallationScope.WORKSPACE
                        && !installation.workspaceId().equals(installation.runtimeWorkspaceId())) {
                    throw new IllegalStateException("WORKSPACE 运行目标与固定工作区不一致");
                }
                Path workspacePath = workspaceAccess.requireWorkspace(installation.runtimeWorkspaceId(),
                        installation.actorUserId(), WorkspacePermission.OPERATOR).workspacePath();
                if (!installation.runtimeImageConfirmed() || installation.runtimeImage().isBlank()) {
                    throw new IllegalStateException("MCP 运行镜像未确认");
                }
                McpRuntimeMaterialProvider.PreparedMaterial material = materialProvider.requirePrepared(aggregate.snapshot());
                McpDockerLaunchSpec spec = configuration.launchSpec(installation, aggregate.snapshot(), material);
                DockerMcpStdioProcess process = runner.attach(spec, installation.containerId(), Map.of(), workspacePath, material.directory(),
                        event -> completeFailure(event, installation.runtimeWorkspaceId()));
                McpStdioTransport transport = new McpStdioTransport(process, objectMapper,
                        configuration.requestTimeout(), configuration.maxStdoutFrameBytes());
                McpClient client = new McpClient(transport, objectMapper, configuration.protocolVersion(),
                        configuration.clientName(), configuration.clientVersion());
                try {
                    new McpToolRegistryAdapter(client, toolRegistry).registerDiscoveredTools(installation.installationId(),
                            installation.riskLevel(), installation.requiredCapabilities(), configuration.toolTimeout());
                    active.put(installation.installationId(), new ActiveRuntime(client, process));
                } catch (RuntimeException exception) {
                    client.close();
                    throw exception;
                }
            } catch (RuntimeException exception) {
                failSynchronously(installation, aggregate, installation.runtimeWorkspaceId(), stableFailureCode(exception));
            }
            return null;
        });
    }

    /** 清理被中断的 INSTALLING 残留，并仅在没有密钥需求时以空环境重新启动。 */
    public void recoverInstalling(McpInstallationAggregate aggregate, DockerMcpContainer container) {
        McpInstallationRecord installation = aggregate.installation();
        within(installation.installationId(), () -> {
            try {
                if (container != null) {
                    McpRuntimeMaterialProvider.PreparedMaterial material = materialProvider.requirePrepared(aggregate.snapshot());
                    runner.destroyManagedContainer(configuration.launchSpec(installation, aggregate.snapshot(), material),
                            container.containerId());
                }
                if (!aggregate.snapshot().environmentVariableNames().isEmpty()) {
                    failSynchronously(installation, aggregate, installation.runtimeWorkspaceId(),
                            "RECOVERY_INSTALLING_SECRETS_UNAVAILABLE");
                    return null;
                }
                if (installation.runtimeWorkspaceId() == null) {
                    failSynchronously(installation, aggregate, null, "RECOVERY_WORKSPACE_UNAVAILABLE");
                    return null;
                }
                try {
                    workspaceAccess.requireWorkspace(installation.runtimeWorkspaceId(), installation.actorUserId(),
                            WorkspacePermission.OPERATOR);
                } catch (WorkspaceAccessService.WorkspaceAccessDeniedException exception) {
                    failSynchronously(installation, aggregate, installation.runtimeWorkspaceId(),
                            "RECOVERY_WORKSPACE_ACCESS_DENIED");
                    return null;
                } catch (WorkspaceAccessService.WorkspaceNotFoundException exception) {
                    failSynchronously(installation, aggregate, installation.runtimeWorkspaceId(),
                            "RECOVERY_WORKSPACE_UNAVAILABLE");
                    return null;
                }
                // INSTALLING 已占用版本；由恢复的受管启动路径以当前版本继续完成。
                restartInstalling(aggregate);
            } catch (RuntimeException exception) {
                failSynchronously(installation, aggregate, installation.runtimeWorkspaceId(), stableFailureCode(exception));
            }
            return null;
        });
    }

    private void restartInstalling(McpInstallationAggregate aggregate) {
        McpInstallationRecord installation = aggregate.installation();
        McpRuntimeMaterialProvider.PreparedMaterial material = materialProvider.requirePrepared(aggregate.snapshot());
        Path workspacePath = workspaceAccess.requireWorkspace(installation.runtimeWorkspaceId(), installation.actorUserId(),
                WorkspacePermission.OPERATOR).workspacePath();
        McpDockerLaunchSpec spec = configuration.launchSpec(installation, aggregate.snapshot(), material);
        DockerMcpStdioProcess process = (DockerMcpStdioProcess) runner.start(spec, Map.of(), workspacePath, material.directory(),
                event -> completeFailure(event, installation.runtimeWorkspaceId()));
        McpStdioTransport transport = new McpStdioTransport(process, objectMapper, configuration.requestTimeout(),
                configuration.maxStdoutFrameBytes());
        McpClient client = new McpClient(transport, objectMapper, configuration.protocolVersion(),
                configuration.clientName(), configuration.clientVersion());
        try {
            List<McpToolRegistryAdapter.ToolBinding> discovered = new McpToolRegistryAdapter(client, toolRegistry)
                    .registerDiscoveredTools(installation.installationId(), installation.riskLevel(),
                            installation.requiredCapabilities(), configuration.toolTimeout());
            List<McpToolBindingRecord> bindings = discovered.stream().map(binding -> new McpToolBindingRecord(
                    installation.installationId(), binding.localToolName(), binding.remoteToolName(), installation.riskLevel(),
                    installation.requiredCapabilities(), clock.instant())).toList();
            McpInstallationRecord running = repository.completeStart(new McpRuntimeStartCompletion(installation.installationId(),
                    installation.version(), installation.runtimeWorkspaceId(), process.containerId(), bindings,
                    audit(aggregate, installation.runtimeWorkspaceId(), "MCP_INSTALLATION_RECOVERED", "SUCCESS",
                            McpInstallationStatus.INSTALLING, McpInstallationStatus.RUNNING)));
            active.put(running.installationId(), new ActiveRuntime(client, process));
        } catch (RuntimeException exception) {
            client.close();
            throw exception;
        }
    }

    /** 正常关闭只清理当前资源；不会把预期关闭写为 FAILED。 */
    public void closeNormally() {
        close();
    }

    /** 恢复 STOPPING 状态时继续已开始的 drain、容器销毁与状态收敛。 */
    public void recoverStopping(McpInstallationAggregate aggregate, DockerMcpContainer container) {
        McpInstallationRecord installation = aggregate.installation();
        within(installation.installationId(), () -> {
            try {
                if (installation.runtimeWorkspaceId() == null || container == null || !container.running()) {
                    throw new IllegalStateException("停止恢复缺少受管运行容器");
                }
                Path workspacePath = workspaceAccess.requireWorkspace(installation.runtimeWorkspaceId(),
                        installation.actorUserId(), WorkspacePermission.OPERATOR).workspacePath();
                McpRuntimeMaterialProvider.PreparedMaterial material = materialProvider.requirePrepared(aggregate.snapshot());
                DockerMcpStdioProcess process = runner.attach(configuration.launchSpec(installation, aggregate.snapshot(), material),
                        container.containerId(), Map.of(), workspacePath, material.directory(), event -> { });
                try {
                    toolRegistry.beginDrain(installation.installationId().toString());
                    toolRegistry.unregisterOwned(installation.installationId().toString(), configuration.drainTimeout());
                } catch (RuntimeException ignored) { }
                process.destroy();
                repository.completeStop(new McpRuntimeStopCompletion(installation.installationId(), installation.version(),
                        audit(aggregate, installation.runtimeWorkspaceId(), "MCP_INSTALLATION_STOPPED", "SUCCESS",
                                McpInstallationStatus.STOPPING, McpInstallationStatus.STOPPED)));
            } catch (RuntimeException exception) {
                failSynchronously(installation, aggregate, installation.runtimeWorkspaceId(), stableFailureCode(exception));
            }
            return null;
        });
    }

    private McpInstallationRecord startInternal(Actor actor, UUID requestWorkspaceId, UUID installationId, LifecycleRequest request) {
        requireStartRequest(request);
        McpInstallationAggregate aggregate = repository.findInstallation(installationId, actor.userId(), requestWorkspaceId)
                .orElseThrow(() -> new McpInstallationConflictException(installationId, request.expectedVersion()));
        Path workspacePath = authorizeWorkspace(actor, aggregate.installation(), request.targetWorkspaceId());
        McpInstallationRecord installing = repository.beginStart(installationId, actor.userId(), requestWorkspaceId,
                request.targetWorkspaceId(), request.expectedVersion(), audit(aggregate, request.targetWorkspaceId(), "MCP_INSTALLATION_STARTING", "SUCCESS",
                        aggregate.installation().status(), McpInstallationStatus.INSTALLING));
        try {
            if (!installing.runtimeImageConfirmed() || installing.runtimeImage().isBlank()) {
                throw new IllegalStateException("MCP 运行镜像未确认");
            }
            McpRuntimeMaterialProvider.PreparedMaterial material = materialProvider.requirePrepared(aggregate.snapshot());
            Map<String, String> environment = secretProvider.resolve(aggregate.snapshot(), request.environment());
            McpDockerLaunchSpec spec = configuration.launchSpec(installing, aggregate.snapshot(), material);
            DockerMcpStdioProcess process = (DockerMcpStdioProcess) runner.start(spec, environment, workspacePath, material.directory(),
                    event -> completeFailure(event, requestWorkspaceId));
            McpStdioTransport transport = new McpStdioTransport(process, objectMapper,
                    configuration.requestTimeout(), configuration.maxStdoutFrameBytes());
            McpClient client = new McpClient(transport, objectMapper, configuration.protocolVersion(),
                    configuration.clientName(), configuration.clientVersion());
            List<McpToolRegistryAdapter.ToolBinding> discovered;
            try {
                discovered = new McpToolRegistryAdapter(client, toolRegistry).registerDiscoveredTools(
                        installing.installationId(), installing.riskLevel(), installing.requiredCapabilities(),
                        configuration.toolTimeout());
            } catch (RuntimeException exception) {
                client.close();
                process.destroy();
                throw exception;
            }
            List<McpToolBindingRecord> bindings = discovered.stream().map(binding -> new McpToolBindingRecord(
                    installing.installationId(), binding.localToolName(), binding.remoteToolName(),
                    installing.riskLevel(), installing.requiredCapabilities(), clock.instant())).toList();
            McpInstallationRecord running;
            try {
                running = repository.completeStart(new McpRuntimeStartCompletion(
                        installing.installationId(), installing.version(), request.targetWorkspaceId(), process.containerId(), bindings,
                        audit(aggregate, requestWorkspaceId, "MCP_INSTALLATION_STARTED", "SUCCESS",
                                McpInstallationStatus.INSTALLING, McpInstallationStatus.RUNNING)));
            } catch (RuntimeException completionFailure) {
                cleanupUncommittedStart(installing.installationId(), client, process, completionFailure);
                throw completionFailure;
            }
            active.put(running.installationId(), new ActiveRuntime(client, process));
            return running;
        } catch (RuntimeException exception) {
            failSynchronously(installing, aggregate, requestWorkspaceId, stableFailureCode(exception));
            throw exception;
        }
    }

    private McpInstallationRecord stopInternal(Actor actor, UUID requestWorkspaceId, UUID installationId, LifecycleRequest request) {
        requireStopRequest(request);
        McpInstallationAggregate aggregate = repository.findInstallation(installationId, actor.userId(), requestWorkspaceId)
                .orElseThrow(() -> new McpInstallationConflictException(installationId, request.expectedVersion()));
        authorizeWorkspace(actor, aggregate.installation(), request.targetWorkspaceId());
        McpInstallationRecord stopping = repository.beginStop(installationId, actor.userId(), requestWorkspaceId,
                request.expectedVersion(), audit(aggregate, requestWorkspaceId, "MCP_INSTALLATION_STOPPING", "SUCCESS",
                        aggregate.installation().status(), McpInstallationStatus.STOPPING));
        ActiveRuntime runtime = active.remove(installationId);
        try {
            toolRegistry.beginDrain(installationId.toString());
            toolRegistry.unregisterOwned(installationId.toString(), configuration.drainTimeout());
            if (runtime != null) runtime.close();
            return repository.completeStop(new McpRuntimeStopCompletion(stopping.installationId(), stopping.version(),
                    audit(aggregate, requestWorkspaceId, "MCP_INSTALLATION_STOPPED", "SUCCESS",
                            McpInstallationStatus.STOPPING, McpInstallationStatus.STOPPED)));
        } catch (RuntimeException exception) {
            failSynchronously(stopping, aggregate, requestWorkspaceId, stableFailureCode(exception));
            throw exception;
        }
    }

    private void completeFailure(McpRuntimeFailureListener.Event event, UUID requestWorkspaceId) {
        if (closing) return;
        within(event.installationId(), () -> {
            ActiveRuntime runtime = active.remove(event.installationId());
            if (runtime != null) runtime.close();
            try {
                toolRegistry.beginDrain(event.installationId().toString());
                toolRegistry.unregisterOwned(event.installationId().toString(), configuration.drainTimeout());
            } catch (RuntimeException ignored) {
                // 失败回调可能发生在工具尚未注册之前。
            }
            repository.findRecoverableInstallations().stream()
                    .filter(value -> value.installation().installationId().equals(event.installationId()))
                    .findFirst().ifPresent(aggregate -> failSynchronously(aggregate.installation(), aggregate,
                            requestWorkspaceId, event.reason().name()));
            return null;
        });
    }

    private void failSynchronously(McpInstallationRecord current, McpInstallationAggregate aggregate,
                                   UUID requestWorkspaceId, String code) {
        try {
            repository.completeFailure(new McpRuntimeFailureCompletion(current.installationId(), current.version(), code,
                    audit(aggregate, requestWorkspaceId, "MCP_INSTALLATION_FAILED", "FAILED",
                            current.status(), McpInstallationStatus.FAILED)));
        } catch (McpInstallationConflictException ignored) {
            // 并发失败回调或显式停止已推进版本时不覆盖其结果。
        }
    }

    /** completeStart 未提交时撤销已暴露工具并销毁容器，再收敛持久化状态。 */
    private void cleanupUncommittedStart(UUID installationId, McpClient client, DockerMcpStdioProcess process,
                                         RuntimeException original) {
        try { toolRegistry.beginDrain(installationId.toString()); }
        catch (RuntimeException cleanupFailure) { original.addSuppressed(cleanupFailure); }
        try { toolRegistry.unregisterOwned(installationId.toString(), configuration.drainTimeout()); }
        catch (RuntimeException cleanupFailure) { original.addSuppressed(cleanupFailure); }
        try { client.close(); }
        catch (RuntimeException cleanupFailure) { original.addSuppressed(cleanupFailure); }
        try { process.destroy(); }
        catch (RuntimeException cleanupFailure) { original.addSuppressed(cleanupFailure); }
    }

    private Path authorizeWorkspace(Actor actor, McpInstallationRecord installation, UUID targetWorkspaceId) {
        if (targetWorkspaceId == null) throw new IllegalArgumentException("targetWorkspaceId 不能为空");
        if (installation.scope() == InstallationScope.WORKSPACE && !installation.workspaceId().equals(targetWorkspaceId)) {
            throw new IllegalArgumentException("WORKSPACE 安装的 targetWorkspaceId 必须与安装记录一致");
        }
        return workspaceAccess.requireWorkspace(targetWorkspaceId, actor.userId(), WorkspacePermission.OPERATOR).workspacePath();
    }

    private CapabilityManagementAuditEvent audit(McpInstallationAggregate aggregate, UUID workspaceId,
                                                 String eventType, String result,
                                                 McpInstallationStatus from, McpInstallationStatus to) {
        return new CapabilityManagementAuditEvent(eventType, aggregate.installation().actorUserId(), workspaceId,
                aggregate.installation().installationId(), null, null, aggregate.snapshot().commitSha(), result,
                clock.instant(), UUID.randomUUID(), from.name(), to.name(), "");
    }

    private static void requireStartRequest(LifecycleRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        if (request.targetWorkspaceId() == null) throw new IllegalArgumentException("targetWorkspaceId 不能为空");
    }

    private static void requireStopRequest(LifecycleRequest request) {
        requireStartRequest(request);
        if (!request.environment().isEmpty()) throw new IllegalArgumentException("stop 的 environment 必须为空");
    }

    private static String stableFailureCode(Throwable exception) {
        if (exception instanceof McpMaterialNotPreparedException) return "MATERIAL_NOT_PREPARED";
        return exception.getClass().getSimpleName();
    }

    private <T> T within(UUID installationId, java.util.concurrent.Callable<T> action) {
        Object lock = locks.computeIfAbsent(installationId, ignored -> new Object());
        synchronized (lock) {
            try { return action.call(); }
            catch (RuntimeException exception) { throw exception; }
            catch (Exception exception) { throw new IllegalStateException("MCP 生命周期执行失败", exception); }
        }
    }

    @Override
    public void close() {
        closing = true;
        active.values().forEach(ActiveRuntime::close);
        active.clear();
    }

    /** 生命周期 API 的固定输入，不持久化目标工作区或环境变量值。 */
    public record LifecycleRequest(long expectedVersion, UUID targetWorkspaceId, Map<String, String> environment) {
        public LifecycleRequest {
            if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion 不能小于 0");
            environment = Map.copyOf(environment == null ? Map.of() : environment);
        }
    }

    /** 运行时连接三元组；关闭 transport 会销毁对应 Docker 容器。 */
    private record ActiveRuntime(McpClient client, DockerMcpStdioProcess process) implements AutoCloseable {
        @Override public void close() { client.close(); }
    }

    /** 将配置和协议标识注入运行时，避免从 HTTP MCP 配置继承配额。 */
    public record McpRuntimeConfiguration(
            String protocolVersion, String clientName, String clientVersion, String materialContainerDirectory,
            String materialSourceContainer, String materialSourcePath, String containerWorkingDirectory,
            long memoryBytes, long nanoCpus, long pidsLimit, int maxStdoutFrameBytes,
            int maxStdoutBufferedBytes, int maxStderrBytes, java.time.Duration requestTimeout,
            java.time.Duration toolTimeout, java.time.Duration drainTimeout) {
        public McpRuntimeConfiguration {
            if (protocolVersion == null || protocolVersion.isBlank() || clientName == null || clientName.isBlank()
                    || clientVersion == null || clientVersion.isBlank() || materialContainerDirectory == null
                    || materialContainerDirectory.isBlank() || containerWorkingDirectory == null
                    || containerWorkingDirectory.isBlank()) throw new IllegalArgumentException("MCP 运行配置字符串不能为空");
            materialSourceContainer = materialSourceContainer == null ? "" : materialSourceContainer.trim();
            materialSourcePath = materialSourcePath == null ? "" : materialSourcePath.trim();
            if (materialSourceContainer.isBlank() != materialSourcePath.isBlank()) {
                throw new IllegalArgumentException("materialSourceContainer 与 materialSourcePath 必须同时配置");
            }
            if (memoryBytes <= 0 || nanoCpus <= 0 || pidsLimit <= 0 || maxStdoutFrameBytes <= 0
                    || maxStdoutBufferedBytes <= 0 || maxStderrBytes <= 0) throw new IllegalArgumentException("MCP 运行配额必须为正数");
            if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()
                    || toolTimeout == null || toolTimeout.isZero() || toolTimeout.isNegative()
                    || drainTimeout == null || drainTimeout.isZero() || drainTimeout.isNegative()) {
                throw new IllegalArgumentException("MCP 运行时长必须为正数");
            }
        }

        McpDockerLaunchSpec launchSpec(McpInstallationRecord installation,
                                       com.agent.web.mcp.installation.McpSourceSnapshot snapshot,
                                       McpRuntimeMaterialProvider.PreparedMaterial material) {
            return new McpDockerLaunchSpec(installation.installationId(), snapshot.snapshotId(), installation.runtimeImage(),
                    material.command(), material.arguments(), materialContainerDirectory, materialSourceContainer, materialSourcePath,
                    containerWorkingDirectory, installation.workspaceMountMode(),
                    installation.networkMode().name().toLowerCase(java.util.Locale.ROOT), memoryBytes, nanoCpus, pidsLimit,
                    maxStdoutFrameBytes, maxStdoutBufferedBytes, maxStderrBytes,
                    new java.util.LinkedHashSet<>(snapshot.environmentVariableNames()));
        }
    }
}
