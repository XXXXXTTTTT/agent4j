package com.agent.web.persistence;

import com.agent.web.capability.InstallationScope;
import com.agent.web.mcp.installation.McpInstallationRecord;
import com.agent.web.mcp.installation.McpInstallationRepository;
import com.agent.web.mcp.installation.McpInstallationStatus;
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
    public McpSourceSnapshot saveSnapshot(McpSourceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot 不能为空");
        return Objects.requireNonNull(transactions.execute(status -> {
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
        }), "MCP 源快照保存事务返回值不能为空");
    }

    @Override
    public McpInstallationRecord saveInstallation(McpInstallationRecord installation) {
        Objects.requireNonNull(installation, "installation 不能为空");
        return Objects.requireNonNull(transactions.execute(status -> {
            jdbc.sql("""
                    insert into agent_mcp_installations (
                        installation_id, snapshot_id, scope, workspace_id, actor_user_id, status,
                        confirmation_token_sha256, created_at, confirmed_at, updated_at
                    ) values (
                        :installationId, :snapshotId, :scope, :workspaceId, :actorUserId, :status,
                        :confirmationTokenSha256, :createdAt, :confirmedAt, :updatedAt
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
                    .update();
            return findInstallation(installation.installationId()).orElse(installation);
        }), "MCP 安装保存事务返回值不能为空");
    }

    @Override
    public List<McpInstallationRecord> findInstallations(String actorUserId, UUID workspaceId) {
        Objects.requireNonNull(actorUserId, "actorUserId 不能为空");
        return jdbc.sql("""
                select installation_id, snapshot_id, scope, workspace_id, actor_user_id, status,
                       confirmation_token_sha256, created_at, confirmed_at, updated_at
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
    public boolean deleteInstallation(UUID installationId, String actorUserId, UUID workspaceId) {
        return jdbc.sql("""
                delete from agent_mcp_installations
                where installation_id = :installationId
                  and actor_user_id = :actorUserId
                  and ((scope = 'WORKSPACE' and workspace_id = :workspaceId) or scope = 'USER_GLOBAL')
                """)
                .param("installationId", installationId)
                .param("actorUserId", actorUserId)
                .param("workspaceId", workspaceId)
                .update() > 0;
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
                       confirmation_token_sha256, created_at, confirmed_at, updated_at
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
                rs.getTimestamp("confirmed_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
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
