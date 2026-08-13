package com.agent.web.mcp.installation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** MCP 安装持久化端口。 */
public interface McpInstallationRepository {
    McpInstallationRecord confirmInstallation(McpInstallationCommand command);
    List<McpInstallationRecord> findInstallations(String actorUserId, UUID workspaceId);

    /** 读取工作台所需的安装记录及其冻结快照环境变量名。 */
    default List<McpInstallationDetails> findInstallationDetails(
            String actorUserId, UUID workspaceId) {
        return findInstallations(actorUserId, workspaceId).stream()
                .map(installation -> new McpInstallationDetails(installation, List.of()))
                .toList();
    }
    McpInstallationRecord removeInstallation(UUID installationId, String actorUserId, UUID workspaceId,
                                             long expectedVersion,
                                             com.agent.web.capability.CapabilityManagementAuditEvent auditEvent);


    default Optional<McpInstallationAggregate> findInstallation(
            UUID installationId, String actorUserId, UUID requestWorkspaceId) {
        throw new UnsupportedOperationException("当前 MCP 仓储不支持安装聚合读取");
    }

    default List<McpInstallationAggregate> findRecoverableInstallations() {
        throw new UnsupportedOperationException("当前 MCP 仓储不支持运行恢复");
    }

    /** 读取当前主体在工作区可用且正在运行的 MCP 安装及其固定工具绑定。 */
    default List<McpInstallationAggregate> findRunningInstallations(
            String actorUserId, UUID workspaceId) {
        throw new UnsupportedOperationException("当前 MCP 仓储不支持运行中安装读取");
    }

    /** 按固定快照标识读取已准备物料，供运行时在启动前重新校验。 */
    default Optional<McpPreparedMaterialRecord> findPreparedMaterial(UUID snapshotId) {
        throw new UnsupportedOperationException("当前 MCP 仓储不支持物料读取");
    }

    default McpInstallationRecord completeMaterialPreparation(UUID installationId, String actorUserId,
                                                               UUID requestWorkspaceId, long expectedVersion,
                                                               McpPreparedMaterialRecord material,
                                                               com.agent.web.capability.CapabilityManagementAuditEvent auditEvent) {
        throw new UnsupportedOperationException("当前 MCP 仓储不支持物料准备完成");
    }

    default void recordMaterialPreparationFailure(UUID installationId, String actorUserId, UUID requestWorkspaceId,
                                                  long expectedVersion,
                                                  com.agent.web.capability.CapabilityManagementAuditEvent auditEvent) {
        throw new UnsupportedOperationException("当前 MCP 仓储不支持物料准备失败审计");
    }

    default McpInstallationRecord beginStart(UUID installationId, String actorUserId, UUID requestWorkspaceId,
                                     UUID runtimeWorkspaceId, long expectedVersion,
                                     com.agent.web.capability.CapabilityManagementAuditEvent auditEvent) {
        throw new UnsupportedOperationException("当前 MCP 仓储不支持启动生命周期");
    }

    default McpInstallationRecord completeStart(McpRuntimeStartCompletion completion) {
        throw new UnsupportedOperationException("当前 MCP 仓储不支持启动完成");
    }

    default McpInstallationRecord completeFailure(McpRuntimeFailureCompletion completion) {
        throw new UnsupportedOperationException("当前 MCP 仓储不支持失败收敛");
    }

    default McpInstallationRecord beginStop(UUID installationId, String actorUserId, UUID requestWorkspaceId,
                                    long expectedVersion,
                                    com.agent.web.capability.CapabilityManagementAuditEvent auditEvent) {
        throw new UnsupportedOperationException("当前 MCP 仓储不支持停止生命周期");
    }

    default McpInstallationRecord completeStop(McpRuntimeStopCompletion completion) {
        throw new UnsupportedOperationException("当前 MCP 仓储不支持停止完成");
    }

    /** 过渡兼容旧测试适配器；生产实现不得使用该端口。 */
    @Deprecated
    default McpInstallationRecord transition(UUID installationId, long expectedVersion, McpInstallationStatus from,
                                             McpInstallationStatus to, String runtimeError, String containerId) {
        throw new UnsupportedOperationException("当前 MCP 仓储不支持旧状态迁移");
    }
}
