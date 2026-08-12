package com.agent.web.persistence;

import com.agent.web.capability.InstallationScope;
import com.agent.web.mcp.installation.McpInstallationRecord;
import com.agent.web.mcp.installation.McpInstallationRepository;
import com.agent.web.mcp.installation.McpInstallationStatus;
import com.agent.web.mcp.installation.McpInstallationCommand;
import com.agent.web.mcp.installation.McpInstallationConflictException;
import com.agent.web.mcp.installation.WorkspaceMountMode;
import com.agent.web.mcp.installation.McpNetworkMode;
import com.agent.core.intent.RequiredCapability;
import com.agent.core.tool.ToolRiskLevel;
import com.agent.web.mcp.installation.McpSourceSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 使用 PostgreSQL 保存已确认的 MCP 源快照和安装记录。 */
public final class JdbcMcpInstallationRepository implements McpInstallationRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    public JdbcMcpInstallationRepository(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不能为空");
        this.transactions = Objects.requireNonNull(transactions, "transactions 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    @Override
    public McpInstallationRecord confirmInstallation(McpInstallationCommand command) {
        Objects.requireNonNull(command, "command 不能为空");
        return Objects.requireNonNull(transactions.execute(status -> {
            McpSourceSnapshot snapshot = saveSnapshotInTransaction(command.snapshot());
            saveInstallationInTransaction(withSnapshotId(command.installation(), snapshot.snapshotId()));
            insertAudit(command.auditEvent());
            return findInstallation(command.installation().installationId()).orElseThrow();
        }), "MCP 聚合事务返回值不能为空");
    }

    private McpSourceSnapshot saveSnapshotInTransaction(McpSourceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot 不能为空");
        jdbc.sql("""
                    insert into agent_mcp_installation_snapshots (
                        snapshot_id, server_key, repository_path, source_url, commit_sha,
                        blob_shas, metadata_sha256, version, description, license, command,
                        arguments, launch_bin, environment_variable_names, readme_summary, created_at
                    ) values (
                        :snapshotId, :serverKey, :repositoryPath, :sourceUrl, :commitSha,
                        cast(:blobShas as jsonb), :metadataSha256, :version, :description, :license, :command,
                        cast(:arguments as jsonb), :launchBin, cast(:environmentNames as jsonb), :readmeSummary, :createdAt
                    )
                    on conflict (server_key, commit_sha, metadata_sha256) do nothing
                    """)
                    .param("snapshotId", snapshot.snapshotId())
                    .param("serverKey", snapshot.serverKey())
                    .param("repositoryPath", snapshot.repositoryPath())
                    .param("sourceUrl", snapshot.sourceUrl().toString())
                    .param("commitSha", snapshot.commitSha())
                    .param("blobShas", json(snapshot.blobShas()))
                    .param("metadataSha256", snapshot.metadataSha256())
                    .param("version", snapshot.version())
                    .param("description", snapshot.description())
                    .param("license", snapshot.license())
                    .param("command", snapshot.command())
                    .param("arguments", json(snapshot.arguments()))
                    .param("launchBin", snapshot.launchBin())
                    .param("environmentNames", json(snapshot.environmentVariableNames()))
                    .param("readmeSummary", snapshot.readmeSummary())
                    .param("createdAt", timestamp(snapshot.createdAt()))
                .update();
        return findSnapshot(snapshot.serverKey(), snapshot.commitSha(), snapshot.metadataSha256()).orElse(snapshot);
    }

    private McpInstallationRecord saveInstallationInTransaction(McpInstallationRecord installation) {
        Objects.requireNonNull(installation, "installation 不能为空");
        jdbc.sql("""
                    insert into agent_mcp_installations (
                        installation_id, snapshot_id, scope, workspace_id, actor_user_id, status,
                        confirmation_token_sha256, created_at, confirmed_at, updated_at,
                        risk_level, required_capabilities, workspace_mount_mode, network_mode,
                        runtime_image, runtime_image_confirmed, runtime_workspace_id, container_id, runtime_error, version
                    ) values (
                        :installationId, :snapshotId, :scope, :workspaceId, :actorUserId, :status,
                        :confirmationTokenSha256, :createdAt, :confirmedAt, :updatedAt,
                        :riskLevel, cast(:requiredCapabilities as jsonb), :workspaceMountMode, :networkMode,
                        :runtimeImage, :runtimeImageConfirmed, :runtimeWorkspaceId, :containerId, :runtimeError, :version
                    )
                    """)
                    .param("installationId", installation.installationId())
                    .param("snapshotId", installation.snapshotId())
                    .param("scope", installation.scope().name())
                    .param("workspaceId", installation.workspaceId())
                    .param("actorUserId", installation.actorUserId())
                    .param("status", installation.status().name())
                    .param("confirmationTokenSha256", installation.confirmationTokenSha256())
                    .param("createdAt", timestamp(installation.createdAt()))
                    .param("confirmedAt", timestamp(installation.confirmedAt()))
                    .param("updatedAt", timestamp(installation.updatedAt()))
                    .param("riskLevel", installation.riskLevel().name())
                    .param("requiredCapabilities", json(installation.requiredCapabilities().stream().map(Enum::name).toList()))
                    .param("workspaceMountMode", installation.workspaceMountMode().name())
                    .param("networkMode", installation.networkMode().name())
                    .param("runtimeImage", installation.runtimeImage())
                    .param("runtimeImageConfirmed", installation.runtimeImageConfirmed())
                    .param("runtimeWorkspaceId", installation.runtimeWorkspaceId())
                    .param("containerId", installation.containerId())
                    .param("runtimeError", installation.runtimeError())
                    .param("version", installation.version())
                .update();
        return findInstallation(installation.installationId()).orElse(installation);
    }

    private static McpInstallationRecord withSnapshotId(McpInstallationRecord installation, UUID snapshotId) {
        return new McpInstallationRecord(installation.installationId(), snapshotId, installation.scope(),
                installation.workspaceId(), installation.actorUserId(), installation.status(),
                installation.confirmationTokenSha256(), installation.createdAt(), installation.confirmedAt(),
                installation.updatedAt(), installation.riskLevel(), installation.requiredCapabilities(),
                installation.workspaceMountMode(), installation.networkMode(), installation.runtimeImage(),
                installation.runtimeImageConfirmed(), installation.runtimeWorkspaceId(), installation.containerId(), installation.runtimeError(), installation.version());
    }

    @Override
    public List<McpInstallationRecord> findInstallations(String actorUserId, UUID workspaceId) {
        Objects.requireNonNull(actorUserId, "actorUserId 不能为空");
        return jdbc.sql("""
                select installation_id, snapshot_id, scope, workspace_id, actor_user_id, status,
                       confirmation_token_sha256, created_at, confirmed_at, updated_at,
                       risk_level, required_capabilities, workspace_mount_mode, network_mode,
                       runtime_image, runtime_image_confirmed, runtime_workspace_id, container_id, runtime_error, version
                from agent_mcp_installations
                where actor_user_id = :actorUserId
                  and ((scope = 'WORKSPACE' and workspace_id = :workspaceId)
                       or scope = 'USER_GLOBAL')
                order by updated_at desc, installation_id
                """)
                .param("actorUserId", actorUserId)
                .param("workspaceId", workspaceId)
                .query(this::mapInstallation)
                .list();
    }

    @Override
    public McpInstallationRecord removeInstallation(UUID installationId, String actorUserId, UUID workspaceId,
                                                    long expectedVersion,
                                                    com.agent.web.capability.CapabilityManagementAuditEvent auditEvent) {
        return Objects.requireNonNull(transactions.execute(status -> {
            McpInstallationRecord installation = findInstallation(installationId)
                    .orElseThrow(() -> new McpInstallationConflictException(installationId, expectedVersion));
            int deleted = jdbc.sql("""
                delete from agent_mcp_installations
                where installation_id = :installationId
                  and actor_user_id = :actorUserId
                  and version = :expectedVersion
                  and status in ('STOPPED', 'FAILED', 'REJECTED')
                  and ((scope = 'WORKSPACE' and workspace_id = :workspaceId) or scope = 'USER_GLOBAL')
                """)
                .param("installationId", installationId)
                .param("actorUserId", actorUserId)
                .param("workspaceId", workspaceId)
                .param("expectedVersion", expectedVersion)
                .update();
            if (deleted != 1) throw new McpInstallationConflictException(installationId, expectedVersion);
            insertAudit(auditEvent);
            return installation;
        }), "MCP 删除聚合事务返回值不能为空");
    }

    @Override
    public java.util.Optional<com.agent.web.mcp.installation.McpInstallationAggregate> findInstallation(
            UUID installationId, String actorUserId, UUID requestWorkspaceId) {
        Objects.requireNonNull(installationId, "installationId 不能为空");
        Objects.requireNonNull(actorUserId, "actorUserId 不能为空");
        Objects.requireNonNull(requestWorkspaceId, "requestWorkspaceId 不能为空");
        return jdbc.sql("""
                select i.installation_id, i.snapshot_id, i.scope, i.workspace_id, i.actor_user_id, i.status,
                       i.confirmation_token_sha256, i.created_at, i.confirmed_at, i.updated_at,
                       i.risk_level, i.required_capabilities, i.workspace_mount_mode, i.network_mode,
                       i.runtime_image, i.runtime_image_confirmed, i.runtime_workspace_id, i.container_id, i.runtime_error, i.version
                from agent_mcp_installations i
                where i.installation_id = :installationId and i.actor_user_id = :actorUserId
                  and ((i.scope = 'WORKSPACE' and i.workspace_id = :workspaceId) or i.scope = 'USER_GLOBAL')
                """).param("installationId", installationId).param("actorUserId", actorUserId)
                .param("workspaceId", requestWorkspaceId).query(this::mapInstallation).optional()
                .map(this::aggregate);
    }

    @Override
    public List<com.agent.web.mcp.installation.McpInstallationAggregate> findRecoverableInstallations() {
        return jdbc.sql("""
                select installation_id, snapshot_id, scope, workspace_id, actor_user_id, status,
                       confirmation_token_sha256, created_at, confirmed_at, updated_at,
                       risk_level, required_capabilities, workspace_mount_mode, network_mode,
                       runtime_image, runtime_image_confirmed, runtime_workspace_id, container_id, runtime_error, version
                from agent_mcp_installations
                where status in ('INSTALLING', 'RUNNING', 'STOPPING')
                order by updated_at, installation_id
                """).query(this::mapInstallation).list().stream().map(this::aggregate).toList();
    }

    @Override
    public McpInstallationRecord beginStart(UUID installationId, String actorUserId, UUID requestWorkspaceId,
                                            UUID runtimeWorkspaceId, long expectedVersion,
                                            com.agent.web.capability.CapabilityManagementAuditEvent auditEvent) {
        Objects.requireNonNull(runtimeWorkspaceId, "runtimeWorkspaceId 不能为空");
        return Objects.requireNonNull(transactions.execute(status -> {
            int updated = jdbc.sql("""
                    update agent_mcp_installations set status = 'INSTALLING', runtime_workspace_id = :runtimeWorkspaceId,
                           runtime_error = null, container_id = null, updated_at = current_timestamp, version = version + 1
                    where installation_id = :id and actor_user_id = :actorUserId and version = :expectedVersion
                      and status in ('STOPPED', 'FAILED')
                      and ((scope = 'WORKSPACE' and workspace_id = :requestWorkspaceId
                            and workspace_id = :runtimeWorkspaceId) or scope = 'USER_GLOBAL')
                    """).param("id", installationId).param("actorUserId", actorUserId)
                    .param("requestWorkspaceId", requestWorkspaceId).param("runtimeWorkspaceId", runtimeWorkspaceId)
                    .param("expectedVersion", expectedVersion).update();
            if (updated != 1) throw new McpInstallationConflictException(installationId, expectedVersion);
            insertAudit(auditEvent);
            return findInstallation(installationId).orElseThrow();
        }), "MCP 启动开始聚合事务返回值不能为空");
    }

    @Override
    public McpInstallationRecord completeStart(com.agent.web.mcp.installation.McpRuntimeStartCompletion completion) {
        Objects.requireNonNull(completion, "completion 不能为空");
        return Objects.requireNonNull(transactions.execute(status -> {
            int updated = jdbc.sql("""
                    update agent_mcp_installations set status = 'RUNNING', container_id = :containerId,
                           runtime_error = null, updated_at = current_timestamp, version = version + 1
                    where installation_id = :id and version = :expectedVersion and status = 'INSTALLING'
                      and runtime_workspace_id = :runtimeWorkspaceId
                    """).param("id", completion.installationId()).param("expectedVersion", completion.expectedVersion())
                    .param("containerId", completion.containerId()).param("runtimeWorkspaceId", completion.runtimeWorkspaceId()).update();
            if (updated != 1) throw new McpInstallationConflictException(completion.installationId(), completion.expectedVersion());
            jdbc.sql("delete from agent_mcp_tool_bindings where installation_id = :id")
                    .param("id", completion.installationId()).update();
            for (com.agent.web.mcp.installation.McpToolBindingRecord binding : completion.bindings()) {
                jdbc.sql("""
                        insert into agent_mcp_tool_bindings (
                            installation_id, local_tool_name, remote_tool_name, risk_level, required_capabilities, created_at)
                        values (:installationId, :localToolName, :remoteToolName, :riskLevel,
                            cast(:requiredCapabilities as jsonb), :createdAt)
                        """).param("installationId", binding.installationId()).param("localToolName", binding.localToolName())
                        .param("remoteToolName", binding.remoteToolName()).param("riskLevel", binding.riskLevel().name())
                        .param("requiredCapabilities", json(binding.requiredCapabilities().stream().map(Enum::name).toList()))
                        .param("createdAt", timestamp(binding.createdAt())).update();
            }
            insertAudit(completion.auditEvent());
            return findInstallation(completion.installationId()).orElseThrow();
        }), "MCP 启动完成聚合事务返回值不能为空");
    }

    @Override
    public McpInstallationRecord completeFailure(com.agent.web.mcp.installation.McpRuntimeFailureCompletion completion) {
        Objects.requireNonNull(completion, "completion 不能为空");
        return Objects.requireNonNull(transactions.execute(status -> {
            int updated = jdbc.sql("""
                    update agent_mcp_installations set status = 'FAILED', container_id = null, runtime_workspace_id = null,
                           runtime_error = :runtimeError, updated_at = current_timestamp, version = version + 1
                    where installation_id = :id and version = :expectedVersion and status in ('INSTALLING', 'RUNNING', 'STOPPING')
                    """).param("id", completion.installationId()).param("expectedVersion", completion.expectedVersion())
                    .param("runtimeError", completion.runtimeError()).update();
            if (updated != 1) throw new McpInstallationConflictException(completion.installationId(), completion.expectedVersion());
            jdbc.sql("delete from agent_mcp_tool_bindings where installation_id = :id")
                    .param("id", completion.installationId()).update();
            insertAudit(completion.auditEvent());
            return findInstallation(completion.installationId()).orElseThrow();
        }), "MCP 失败完成聚合事务返回值不能为空");
    }

    @Override
    public McpInstallationRecord beginStop(UUID installationId, String actorUserId, UUID requestWorkspaceId,
                                           long expectedVersion,
                                           com.agent.web.capability.CapabilityManagementAuditEvent auditEvent) {
        return transition(installationId, actorUserId, requestWorkspaceId, expectedVersion,
                List.of(McpInstallationStatus.RUNNING), McpInstallationStatus.STOPPING, null, null, auditEvent);
    }

    @Override
    public McpInstallationRecord completeStop(com.agent.web.mcp.installation.McpRuntimeStopCompletion completion) {
        Objects.requireNonNull(completion, "completion 不能为空");
        return Objects.requireNonNull(transactions.execute(status -> {
            int updated = jdbc.sql("""
                    update agent_mcp_installations set status = 'STOPPED', container_id = null, runtime_workspace_id = null,
                           runtime_error = null, updated_at = current_timestamp, version = version + 1
                    where installation_id = :id and version = :expectedVersion and status = 'STOPPING'
                    """).param("id", completion.installationId()).param("expectedVersion", completion.expectedVersion()).update();
            if (updated != 1) throw new McpInstallationConflictException(completion.installationId(), completion.expectedVersion());
            jdbc.sql("delete from agent_mcp_tool_bindings where installation_id = :id")
                    .param("id", completion.installationId()).update();
            insertAudit(completion.auditEvent());
            return findInstallation(completion.installationId()).orElseThrow();
        }), "MCP 停止完成聚合事务返回值不能为空");
    }

    private McpInstallationRecord transition(UUID installationId, String actorUserId, UUID workspaceId,
                                             long expectedVersion, List<McpInstallationStatus> from,
                                             McpInstallationStatus to, String runtimeError, String containerId,
                                             com.agent.web.capability.CapabilityManagementAuditEvent auditEvent) {
        return Objects.requireNonNull(transactions.execute(status -> {
            int updated = jdbc.sql("""
                    update agent_mcp_installations set status = :to, runtime_error = :runtimeError,
                           container_id = :containerId, updated_at = current_timestamp, version = version + 1
                    where installation_id = :id and actor_user_id = :actorUserId and version = :expectedVersion
                      and status = any(cast(:statuses as varchar[]))
                      and ((scope = 'WORKSPACE' and workspace_id = :workspaceId) or scope = 'USER_GLOBAL')
                    """).param("to", to.name()).param("runtimeError", runtimeError).param("containerId", containerId)
                    .param("id", installationId).param("actorUserId", actorUserId).param("expectedVersion", expectedVersion)
                    .param("statuses", from.stream().map(Enum::name).toArray(String[]::new)).param("workspaceId", workspaceId).update();
            if (updated != 1) throw new McpInstallationConflictException(installationId, expectedVersion);
            insertAudit(auditEvent);
            return findInstallation(installationId).orElseThrow();
        }), "MCP 生命周期开始聚合事务返回值不能为空");
    }

    private java.util.Optional<McpSourceSnapshot> findSnapshot(String serverKey, String commitSha, String metadataSha256) {
        return jdbc.sql("""
                select snapshot_id, server_key, repository_path, source_url, commit_sha, blob_shas,
                       metadata_sha256, version, description, license, command, arguments, launch_bin,
                       environment_variable_names, readme_summary, created_at
                from agent_mcp_installation_snapshots
                where server_key = :serverKey and commit_sha = :commitSha and metadata_sha256 = :metadataSha256
                """)
                .param("serverKey", serverKey).param("commitSha", commitSha).param("metadataSha256", metadataSha256)
                .query(this::mapSnapshot).optional();
    }

    private java.util.Optional<McpInstallationRecord> findInstallation(UUID installationId) {
        return jdbc.sql("""
                select installation_id, snapshot_id, scope, workspace_id, actor_user_id, status,
                       confirmation_token_sha256, created_at, confirmed_at, updated_at,
                       risk_level, required_capabilities, workspace_mount_mode, network_mode,
                       runtime_image, runtime_image_confirmed, runtime_workspace_id, container_id, runtime_error, version
                from agent_mcp_installations where installation_id = :installationId
                """)
                .param("installationId", installationId).query(this::mapInstallation).optional();
    }

    private com.agent.web.mcp.installation.McpInstallationAggregate aggregate(McpInstallationRecord installation) {
        McpSourceSnapshot snapshot = findSnapshotById(installation.snapshotId()).orElseThrow(
                () -> new IllegalStateException("MCP 安装快照不存在: " + installation.snapshotId()));
        return new com.agent.web.mcp.installation.McpInstallationAggregate(
                installation, snapshot, findPreparedMaterial(snapshot.snapshotId()).orElse(null),
                findBindings(installation.installationId()));
    }

    private java.util.Optional<McpSourceSnapshot> findSnapshotById(UUID snapshotId) {
        return jdbc.sql("""
                select snapshot_id, server_key, repository_path, source_url, commit_sha, blob_shas,
                       metadata_sha256, version, description, license, command, arguments, launch_bin,
                       environment_variable_names, readme_summary, created_at
                from agent_mcp_installation_snapshots where snapshot_id = :snapshotId
                """).param("snapshotId", snapshotId).query(this::mapSnapshot).optional();
    }

    @Override
    public java.util.Optional<com.agent.web.mcp.installation.McpPreparedMaterialRecord> findPreparedMaterial(UUID snapshotId) {
        return jdbc.sql("""
                select material_directory, material_sha256, material_command, material_arguments, material_prepared_at
                from agent_mcp_installation_snapshots where snapshot_id = :snapshotId
                  and material_directory is not null
                """).param("snapshotId", snapshotId).query((rs, row) ->
                new com.agent.web.mcp.installation.McpPreparedMaterialRecord(
                        java.nio.file.Path.of(rs.getString("material_directory")), rs.getString("material_sha256"),
                        rs.getString("material_command"), readList(rs.getString("material_arguments")),
                        rs.getTimestamp("material_prepared_at").toInstant())).optional();
    }

    @Override
    public McpInstallationRecord completeMaterialPreparation(UUID installationId, String actorUserId,
                                                             UUID requestWorkspaceId, long expectedVersion,
                                                             com.agent.web.mcp.installation.McpPreparedMaterialRecord material,
                                                             com.agent.web.capability.CapabilityManagementAuditEvent auditEvent) {
        Objects.requireNonNull(material, "material 不能为空");
        return Objects.requireNonNull(transactions.execute(status -> {
            McpInstallationRecord installation = findInstallation(installationId)
                    .orElseThrow(() -> new McpInstallationConflictException(installationId, expectedVersion));
            int updated = jdbc.sql("""
                    update agent_mcp_installations set version = version + 1, updated_at = current_timestamp
                    where installation_id = :installationId and actor_user_id = :actorUserId and version = :expectedVersion
                      and status in ('STOPPED', 'FAILED')
                      and ((scope = 'WORKSPACE' and workspace_id = :workspaceId) or scope = 'USER_GLOBAL')
                    """).param("installationId", installationId).param("actorUserId", actorUserId)
                    .param("expectedVersion", expectedVersion).param("workspaceId", requestWorkspaceId).update();
            if (updated != 1) throw new McpInstallationConflictException(installationId, expectedVersion);
            int snapshotUpdated = jdbc.sql("""
                    update agent_mcp_installation_snapshots set material_directory = :directory,
                           material_sha256 = :sha256, material_command = :command,
                           material_arguments = cast(:arguments as jsonb), material_prepared_at = :preparedAt
                    where snapshot_id = :snapshotId
                    """).param("directory", material.directory().toString()).param("sha256", material.sha256())
                    .param("command", material.command()).param("arguments", json(material.arguments()))
                    .param("preparedAt", timestamp(material.preparedAt())).param("snapshotId", installation.snapshotId()).update();
            if (snapshotUpdated != 1) throw new McpInstallationConflictException(installationId, expectedVersion);
            insertAudit(auditEvent);
            return findInstallation(installationId).orElseThrow();
        }), "MCP 物料准备聚合事务返回值不能为空");
    }

    @Override
    public void recordMaterialPreparationFailure(UUID installationId, String actorUserId, UUID requestWorkspaceId,
                                                 long expectedVersion,
                                                 com.agent.web.capability.CapabilityManagementAuditEvent auditEvent) {
        Objects.requireNonNull(auditEvent, "auditEvent 不能为空");
        Objects.requireNonNull(transactions.execute(status -> {
            int matched = jdbc.sql("""
                    select count(*) from agent_mcp_installations
                    where installation_id = :installationId and actor_user_id = :actorUserId and version = :expectedVersion
                      and status in ('STOPPED', 'FAILED')
                      and ((scope = 'WORKSPACE' and workspace_id = :workspaceId) or scope = 'USER_GLOBAL')
                    """).param("installationId", installationId).param("actorUserId", actorUserId)
                    .param("expectedVersion", expectedVersion).param("workspaceId", requestWorkspaceId)
                    .query(Integer.class).single();
            if (matched != 1) throw new McpInstallationConflictException(installationId, expectedVersion);
            insertAudit(auditEvent);
            return Boolean.TRUE;
        }), "MCP 物料准备失败审计事务返回值不能为空");
    }

    private List<com.agent.web.mcp.installation.McpToolBindingRecord> findBindings(UUID installationId) {
        return jdbc.sql("""
                select installation_id, local_tool_name, remote_tool_name, risk_level, required_capabilities, created_at
                from agent_mcp_tool_bindings where installation_id = :installationId order by local_tool_name
                """).param("installationId", installationId).query((rs, row) ->
                new com.agent.web.mcp.installation.McpToolBindingRecord(
                        rs.getObject("installation_id", UUID.class), rs.getString("local_tool_name"),
                        rs.getString("remote_tool_name"), ToolRiskLevel.valueOf(rs.getString("risk_level")),
                        readCapabilities(rs.getString("required_capabilities")),
                        rs.getTimestamp("created_at").toInstant())).list();
    }

    private McpSourceSnapshot mapSnapshot(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new McpSourceSnapshot(rs.getObject("snapshot_id", UUID.class), rs.getString("server_key"),
                rs.getString("repository_path"), java.net.URI.create(rs.getString("source_url")), rs.getString("commit_sha"),
                readMap(rs.getString("blob_shas")), rs.getString("metadata_sha256"), rs.getString("version"),
                rs.getString("description"), rs.getString("license"), rs.getString("command"),
                readList(rs.getString("arguments")), rs.getString("launch_bin"), readList(rs.getString("environment_variable_names")),
                rs.getString("readme_summary"), rs.getTimestamp("created_at").toInstant());
    }

    private McpInstallationRecord mapInstallation(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new McpInstallationRecord(rs.getObject("installation_id", UUID.class), rs.getObject("snapshot_id", UUID.class),
                InstallationScope.valueOf(rs.getString("scope")), rs.getObject("workspace_id", UUID.class),
                rs.getString("actor_user_id"), McpInstallationStatus.valueOf(rs.getString("status")),
                rs.getString("confirmation_token_sha256"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("confirmed_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                ToolRiskLevel.valueOf(rs.getString("risk_level")), readCapabilities(rs.getString("required_capabilities")),
                WorkspaceMountMode.valueOf(rs.getString("workspace_mount_mode")), McpNetworkMode.valueOf(rs.getString("network_mode")),
                rs.getString("runtime_image"), rs.getBoolean("runtime_image_confirmed"), rs.getObject("runtime_workspace_id", UUID.class), rs.getString("container_id"), rs.getString("runtime_error"), rs.getLong("version"));
    }

    private void insertAudit(com.agent.web.capability.CapabilityManagementAuditEvent event) {
        jdbc.sql("""
                insert into agent_capability_management_audit (
                    audit_id, event_type, actor_user_id, workspace_id, installation_id, skill_id, run_id,
                    source_commit_sha, result, occurred_at, operation_id, from_status, to_status, detail_sha256)
                values (:auditId, :eventType, :actorUserId, :workspaceId, :installationId, :skillId, :runId,
                    :sourceCommitSha, :result, :occurredAt, :operationId, :fromStatus, :toStatus, :detailSha256)
                """)
                .param("auditId", UUID.randomUUID()).param("eventType", event.eventType())
                .param("actorUserId", event.actorUserId()).param("workspaceId", event.workspaceId())
                .param("installationId", event.installationId()).param("skillId", event.skillId())
                .param("runId", event.runId()).param("sourceCommitSha", event.sourceCommitSha())
                .param("result", event.result()).param("occurredAt", timestamp(event.occurredAt()))
                .param("operationId", event.operationId()).param("fromStatus", event.fromStatus())
                .param("toStatus", event.toStatus()).param("detailSha256", event.detailSha256()).update();
    }

    private java.util.Set<RequiredCapability> readCapabilities(String value) {
        return readList(value).stream().map(RequiredCapability::valueOf).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("MCP JSON 序列化失败", exception); }
    }
    private Map<String, String> readMap(String value) {
        try { return objectMapper.readValue(value, objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class)); }
        catch (Exception exception) { throw new IllegalStateException("MCP blob SHA JSON 无效", exception); }
    }
    private List<String> readList(String value) {
        try { return objectMapper.readValue(value, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)); }
        catch (Exception exception) { throw new IllegalStateException("MCP 参数 JSON 无效", exception); }
    }
    private static Timestamp timestamp(Instant value) { return Timestamp.from(value.truncatedTo(ChronoUnit.MICROS)); }
}
