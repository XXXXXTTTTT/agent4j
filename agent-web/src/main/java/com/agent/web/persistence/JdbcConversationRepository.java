package com.agent.web.persistence;

import com.agent.web.identity.Actor;
import com.agent.web.workspace.WorkspacePermission;
import com.agent.web.workspace.WorkspaceRecord;
import com.agent.web.workspace.WorkspaceRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 使用 PostgreSQL 保存用户、工作区和会话数据的 JDBC 适配器。 */
public final class JdbcConversationRepository implements WorkspaceRepository {

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /** 创建 JDBC 会话仓储。 */
    public JdbcConversationRepository(
            JdbcClient jdbcClient,
            TransactionTemplate transactionTemplate,
            Clock clock) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient 不能为空");
        this.transactionTemplate = Objects.requireNonNull(
                transactionTemplate, "transactionTemplate 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /** 查询当前用户可见的工作区。 */
    @Override
    public List<WorkspaceRecord> findWorkspaces(String userId) {
        requireText(userId, "userId");
        return List.copyOf(jdbcClient.sql(workspaceSelect() + """
                where member.user_id = :userId
                  and app_user.enabled = true
                order by workspace.updated_at desc, workspace.workspace_id
                """)
                .param("userId", userId)
                .query(this::mapWorkspace)
                .list());
    }

    /** 查询当前用户在指定工作区的成员记录。 */
    @Override
    public Optional<WorkspaceRecord> findWorkspace(UUID workspaceId, String userId) {
        Objects.requireNonNull(workspaceId, "workspaceId 不能为空");
        requireText(userId, "userId");
        return jdbcClient.sql(workspaceSelect() + """
                where workspace.workspace_id = :workspaceId
                  and member.user_id = :userId
                  and app_user.enabled = true
                """)
                .param("workspaceId", workspaceId)
                .param("userId", userId)
                .query(this::mapWorkspace)
                .optional();
    }

    /** 创建用户、工作区和 OWNER 成员关系。 */
    @Override
    public WorkspaceRecord createWorkspace(
            UUID workspaceId,
            Actor owner,
            String displayName,
            Path workspacePath,
            String repositoryId,
            Instant now) {
        Objects.requireNonNull(workspaceId, "workspaceId 不能为空");
        Objects.requireNonNull(owner, "owner 不能为空");
        requireText(displayName, "displayName");
        Objects.requireNonNull(workspacePath, "workspacePath 不能为空");
        requireText(repositoryId, "repositoryId");
        Objects.requireNonNull(now, "now 不能为空");
        return requireResult(transactionTemplate.execute(status -> {
            ensureUser(owner, now);
            jdbcClient.sql("""
                    insert into agent_workspaces (
                        workspace_id, owner_user_id, display_name, workspace_path,
                        repository_id, created_at, updated_at
                    ) values (
                        :workspaceId, :ownerUserId, :displayName, :workspacePath,
                        :repositoryId, :createdAt, :updatedAt
                    )
                    """)
                    .param("workspaceId", workspaceId)
                    .param("ownerUserId", owner.userId())
                    .param("displayName", displayName)
                    .param("workspacePath", workspacePath.toString())
                    .param("repositoryId", repositoryId)
                    .param("createdAt", timestamp(now))
                    .param("updatedAt", timestamp(now))
                    .update();
            insertMember(workspaceId, owner.userId(), WorkspacePermission.OWNER, now);
            return findWorkspace(workspaceId, owner.userId()).orElseThrow();
        }));
    }

    /** 幂等创建默认工作区，保留首次分配的 workspaceId。 */
    @Override
    public WorkspaceRecord ensureDefaultWorkspace(
            UUID workspaceId,
            Actor owner,
            String displayName,
            Path workspacePath,
            String repositoryId,
            Instant now) {
        Objects.requireNonNull(workspaceId, "workspaceId 不能为空");
        Objects.requireNonNull(owner, "owner 不能为空");
        requireText(displayName, "displayName");
        Objects.requireNonNull(workspacePath, "workspacePath 不能为空");
        requireText(repositoryId, "repositoryId");
        Objects.requireNonNull(now, "now 不能为空");
        return requireResult(transactionTemplate.execute(status -> {
            ensureUser(owner, now);
            jdbcClient.sql("""
                    insert into agent_workspaces (
                        workspace_id, owner_user_id, display_name, workspace_path,
                        repository_id, created_at, updated_at
                    ) values (
                        :workspaceId, :ownerUserId, :displayName, :workspacePath,
                        :repositoryId, :createdAt, :updatedAt
                    )
                    on conflict (owner_user_id, workspace_path) do update set
                        display_name = excluded.display_name,
                        repository_id = excluded.repository_id,
                        updated_at = excluded.updated_at
                    """)
                    .param("workspaceId", workspaceId)
                    .param("ownerUserId", owner.userId())
                    .param("displayName", displayName)
                    .param("workspacePath", workspacePath.toString())
                    .param("repositoryId", repositoryId)
                    .param("createdAt", timestamp(now))
                    .param("updatedAt", timestamp(now))
                    .update();
            UUID actualWorkspaceId = jdbcClient.sql("""
                    select workspace_id from agent_workspaces
                    where owner_user_id = :ownerUserId and workspace_path = :workspacePath
                    """)
                    .param("ownerUserId", owner.userId())
                    .param("workspacePath", workspacePath.toString())
                    .query(UUID.class)
                    .single();
            insertMember(actualWorkspaceId, owner.userId(), WorkspacePermission.OWNER, now);
            return findWorkspace(actualWorkspaceId, owner.userId()).orElseThrow();
        }));
    }

    /** 幂等保存用户，保留已有的 enabled 状态。 */
    @Override
    public void ensureUser(Actor actor, Instant now) {
        Objects.requireNonNull(actor, "actor 不能为空");
        Objects.requireNonNull(now, "now 不能为空");
        jdbcClient.sql("""
                insert into agent_users (
                    user_id, display_name, enabled, created_at, updated_at
                ) values (
                    :userId, :displayName, true, :createdAt, :updatedAt
                )
                on conflict (user_id) do update set
                    display_name = excluded.display_name,
                    updated_at = excluded.updated_at
                """)
                .param("userId", actor.userId())
                .param("displayName", actor.displayName())
                .param("createdAt", timestamp(now))
                .param("updatedAt", timestamp(now))
                .update();
    }

    /** 幂等写入工作区成员权限。 */
    @Override
    public void grantMember(
            UUID workspaceId,
            String userId,
            WorkspacePermission permission,
            Instant now) {
        Objects.requireNonNull(workspaceId, "workspaceId 不能为空");
        requireText(userId, "userId");
        Objects.requireNonNull(permission, "permission 不能为空");
        Objects.requireNonNull(now, "now 不能为空");
        insertMember(workspaceId, userId, permission, now);
    }

    private void insertMember(
            UUID workspaceId,
            String userId,
            WorkspacePermission permission,
            Instant now) {
        jdbcClient.sql("""
                insert into agent_workspace_members (
                    workspace_id, user_id, permission, created_at, updated_at
                ) values (
                    :workspaceId, :userId, :permission, :createdAt, :updatedAt
                )
                on conflict (workspace_id, user_id) do update set
                    permission = excluded.permission,
                    updated_at = excluded.updated_at
                """)
                .param("workspaceId", workspaceId)
                .param("userId", userId)
                .param("permission", permission.name())
                .param("createdAt", timestamp(now))
                .param("updatedAt", timestamp(now))
                .update();
    }

    private String workspaceSelect() {
        return """
                select
                    workspace.workspace_id,
                    workspace.owner_user_id,
                    workspace.display_name,
                    workspace.workspace_path,
                    workspace.repository_id,
                    member.permission,
                    workspace.created_at,
                    workspace.updated_at
                from agent_workspaces workspace
                join agent_workspace_members member
                  on member.workspace_id = workspace.workspace_id
                join agent_users app_user
                  on app_user.user_id = member.user_id
                """;
    }

    private WorkspaceRecord mapWorkspace(java.sql.ResultSet resultSet, int rowNumber)
            throws java.sql.SQLException {
        return new WorkspaceRecord(
                resultSet.getObject("workspace_id", UUID.class),
                resultSet.getString("owner_user_id"),
                resultSet.getString("display_name"),
                Path.of(resultSet.getString("workspace_path")),
                resultSet.getString("repository_id"),
                WorkspacePermission.valueOf(resultSet.getString("permission")),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant.truncatedTo(ChronoUnit.MICROS));
    }

    private <T> T requireResult(T result) {
        return Objects.requireNonNull(result, "事务返回值不能为空");
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " 不能为空").isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
    }
}
