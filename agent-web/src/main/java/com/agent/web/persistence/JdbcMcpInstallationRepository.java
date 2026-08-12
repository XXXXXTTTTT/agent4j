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
                        runtime_image, container_id, runtime_error, version
                    ) values (
                        :installationId, :snapshotId, :scope, :workspaceId, :actorUserId, :status,
                        :confirmationTokenSha256, :createdAt, :confirmedAt, :updatedAt,
                        :riskLevel, cast(:requiredCapabilities as jsonb), :workspaceMountMode, :networkMode,
                        :runtimeImage, :containerId, :runtimeError, :version
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
                installation.containerId(), installation.runtimeError(), installation.version());
    }

    @Override
    public List<McpInstallationRecord> findInstallations(String actorUserId, UUID workspaceId) {
        Objects.requireNonNull(actorUserId, "actorUserId 不能为空");
        return jdbc.sql("""
                select installation_id, snapshot_id, scope, workspace_id, actor_user_id, status,
                       confirmation_token_sha256, created_at, confirmed_at, updated_at,
                       risk_level, required_capabilities, workspace_mount_mode, network_mode,
                       runtime_image, container_id, runtime_error, version
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
    public McpInstallationRecord transition(UUID installationId, long expectedVersion, McpInstallationStatus from,
                                            McpInstallationStatus to, String runtimeError, String containerId) {
        return Objects.requireNonNull(transactions.execute(status -> {
            int updated = jdbc.sql("""
                    update agent_mcp_installations set status = :to, runtime_error = :runtimeError,
                           container_id = :containerId, updated_at = current_timestamp, version = version + 1
                    where installation_id = :id and version = :expectedVersion and status = :from
                    """).param("to", to.name()).param("runtimeError", runtimeError)
                    .param("containerId", containerId).param("id", installationId)
                    .param("expectedVersion", expectedVersion).param("from", from.name()).update();
            if (updated != 1) throw new McpInstallationConflictException(installationId, expectedVersion);
            return findInstallation(installationId).orElseThrow();
        }), "MCP 状态迁移事务返回值不能为空");
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
                       runtime_image, container_id, runtime_error, version
                from agent_mcp_installations where installation_id = :installationId
                """)
                .param("installationId", installationId).query(this::mapInstallation).optional();
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
                rs.getString("runtime_image"), rs.getString("container_id"), rs.getString("runtime_error"), rs.getLong("version"));
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
