package com.agent.web.persistence;

import com.agent.web.capability.InstallationScope;
import com.agent.web.skill.GitHubSkillSnapshot;
import com.agent.web.skill.SkillInstallationRecord;
import com.agent.web.skill.SkillInstallationRepository;
import com.agent.web.skill.SkillInstallationStatus;
import com.agent.web.skill.SkillSnapshotRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 使用 PostgreSQL 保存已确认的 GitHub Skill 快照和安装记录。 */
public final class JdbcSkillInstallationRepository implements SkillInstallationRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    public JdbcSkillInstallationRepository(JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不能为空");
        this.transactions = Objects.requireNonNull(transactions, "transactions 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    @Override
    public SkillSnapshotRecord saveSnapshot(SkillSnapshotRecord snapshot) {
        Objects.requireNonNull(snapshot, "snapshot 不能为空");
        return Objects.requireNonNull(transactions.execute(status -> {
            jdbc.sql("""
                    insert into agent_skill_snapshots (
                        skill_snapshot_id, repository_url, repository, commit_sha, blob_sha, skill_path,
                        license, content_sha256, summary, requested_tool_names, content, created_at
                    ) values (
                        :id, :repositoryUrl, :repository, :commitSha, :blobSha, :path,
                        :license, :contentSha256, :summary, cast(:tools as jsonb), :content, :createdAt
                    ) on conflict (repository, commit_sha, blob_sha, skill_path, content_sha256) do nothing
                    """)
                    .param("id", snapshot.skillSnapshotId())
                    .param("repositoryUrl", snapshot.repositoryUrl().toString())
                    .param("repository", snapshot.repository())
                    .param("commitSha", snapshot.commitSha())
                    .param("blobSha", snapshot.blobSha())
                    .param("path", snapshot.path())
                    .param("license", snapshot.license())
                    .param("contentSha256", snapshot.contentSha256())
                    .param("summary", snapshot.summary())
                    .param("tools", json(snapshot.requestedToolNames()))
                    .param("content", snapshot.content())
                    .param("createdAt", timestamp(snapshot.createdAt()))
                    .update();
            return jdbc.sql("""
                    select skill_snapshot_id, repository_url, repository, commit_sha, blob_sha, skill_path,
                           license, content_sha256, summary, requested_tool_names, content, created_at
                    from agent_skill_snapshots
                    where repository = :repository and commit_sha = :commitSha
                      and blob_sha = :blobSha and skill_path = :path and content_sha256 = :contentSha256
                    """)
                    .param("repository", snapshot.repository()).param("commitSha", snapshot.commitSha())
                    .param("blobSha", snapshot.blobSha()).param("path", snapshot.path())
                    .param("contentSha256", snapshot.contentSha256()).query(this::mapSnapshot).single();
        }), "Skill 快照保存事务返回值不能为空");
    }

    @Override
    public SkillInstallationRecord saveInstallation(SkillInstallationRecord installation) {
        Objects.requireNonNull(installation, "installation 不能为空");
        return Objects.requireNonNull(transactions.execute(status -> {
            jdbc.sql("""
                    insert into agent_skill_installations (
                        skill_installation_id, skill_snapshot_id, scope, workspace_id, actor_user_id,
                        status, confirmation_token_sha256, created_at, confirmed_at, updated_at
                    ) values (
                        :id, :snapshotId, :scope, :workspaceId, :actorUserId,
                        :status, :token, :createdAt, :confirmedAt, :updatedAt
                    )
                    """)
                    .param("id", installation.skillInstallationId()).param("snapshotId", installation.skillSnapshotId())
                    .param("scope", installation.scope().name()).param("workspaceId", installation.workspaceId())
                    .param("actorUserId", installation.actorUserId()).param("status", installation.status().name())
                    .param("token", installation.confirmationTokenSha256()).param("createdAt", timestamp(installation.createdAt()))
                    .param("confirmedAt", timestamp(installation.confirmedAt())).param("updatedAt", timestamp(installation.updatedAt()))
                    .update();
            return jdbc.sql("""
                    select skill_installation_id, skill_snapshot_id, scope, workspace_id, actor_user_id,
                           status, confirmation_token_sha256, created_at, confirmed_at, updated_at
                    from agent_skill_installations where skill_installation_id = :id
                    """).param("id", installation.skillInstallationId()).query(this::mapInstallation).single();
        }), "Skill 安装保存事务返回值不能为空");
    }

    @Override
    public List<SkillInstallationRecord> findInstallations(String actorUserId, UUID workspaceId) {
        return jdbc.sql("""
                select skill_installation_id, skill_snapshot_id, scope, workspace_id, actor_user_id,
                       status, confirmation_token_sha256, created_at, confirmed_at, updated_at
                from agent_skill_installations
                where actor_user_id = :actorUserId
                  and ((scope = 'WORKSPACE' and workspace_id = :workspaceId) or scope = 'USER_GLOBAL')
                order by updated_at desc, skill_installation_id
                """).param("actorUserId", actorUserId).param("workspaceId", workspaceId)
                .query(this::mapInstallation).list();
    }

    @Override
    public boolean deleteInstallation(UUID skillInstallationId, String actorUserId, UUID workspaceId) {
        return jdbc.sql("""
                delete from agent_skill_installations
                where skill_installation_id = :id and actor_user_id = :actorUserId
                  and ((scope = 'WORKSPACE' and workspace_id = :workspaceId) or scope = 'USER_GLOBAL')
                """).param("id", skillInstallationId).param("actorUserId", actorUserId)
                .param("workspaceId", workspaceId).update() > 0;
    }

    private SkillSnapshotRecord mapSnapshot(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new SkillSnapshotRecord(rs.getObject("skill_snapshot_id", UUID.class),
                java.net.URI.create(rs.getString("repository_url")), rs.getString("repository"),
                rs.getString("commit_sha"), rs.getString("blob_sha"), rs.getString("skill_path"),
                rs.getString("license"), rs.getString("content_sha256"), rs.getString("summary"),
                readList(rs.getString("requested_tool_names")), rs.getString("content"),
                rs.getTimestamp("created_at").toInstant());
    }

    private SkillInstallationRecord mapInstallation(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new SkillInstallationRecord(rs.getObject("skill_installation_id", UUID.class),
                rs.getObject("skill_snapshot_id", UUID.class), InstallationScope.valueOf(rs.getString("scope")),
                rs.getObject("workspace_id", UUID.class), rs.getString("actor_user_id"),
                SkillInstallationStatus.valueOf(rs.getString("status")), rs.getString("confirmation_token_sha256"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("confirmed_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private List<String> readList(String value) {
        try { return objectMapper.readValue(value, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)); }
        catch (Exception exception) { throw new IllegalStateException("Skill 工具列表 JSON 无效", exception); }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("Skill JSON 序列化失败", exception); }
    }

    private static Timestamp timestamp(Instant value) { return Timestamp.from(value.truncatedTo(ChronoUnit.MICROS)); }
}
