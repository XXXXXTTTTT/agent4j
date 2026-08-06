package com.agent.web.persistence;

import com.agent.web.identity.Actor;
import com.agent.web.workspace.WorkspacePermission;
import com.agent.web.workspace.WorkspaceRecord;
import com.agent.web.workspace.WorkspaceRepository;
import com.agent.web.conversation.ConversationRecord;
import com.agent.web.conversation.ConversationRepository;
import com.agent.web.conversation.ConversationStatus;
import com.agent.web.conversation.ConversationTurnRecord;
import com.agent.web.conversation.ConversationTurnStatus;
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
public final class JdbcConversationRepository implements WorkspaceRepository, ConversationRepository {

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

    /** 将首轮用户文本折叠空白并截断到 80 个 Unicode code point。 */
    public static String deriveTitle(String userContent) {
        requireText(userContent, "userContent");
        String normalized = userContent.strip().replaceAll("\\s+", " ");
        requireText(normalized, "userContent");
        int codePoints = normalized.codePointCount(0, normalized.length());
        if (codePoints <= 80) {
            return normalized;
        }
        return normalized.substring(0, normalized.offsetByCodePoints(0, 80));
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

    /** 按工作区成员关系查询会话，标题查询保持 PostgreSQL 大小写敏感语义。 */
    @Override
    public List<ConversationRecord> findConversations(
            UUID workspaceId,
            String userId,
            String query) {
        Objects.requireNonNull(workspaceId, "workspaceId 不能为空");
        requireText(userId, "userId");
        String titleQuery = query == null ? "" : query;
        return List.copyOf(jdbcClient.sql("""
                select conversation.conversation_id,
                       conversation.workspace_id,
                       conversation.created_by,
                       conversation.title,
                       conversation.status,
                       conversation.created_at,
                       conversation.updated_at
                from agent_conversations conversation
                join agent_workspace_members member
                  on member.workspace_id = conversation.workspace_id
                 and member.user_id = :userId
                join agent_users app_user on app_user.user_id = member.user_id
                where conversation.workspace_id = :workspaceId
                  and app_user.enabled = true
                  and (:query = '' or position(:query in conversation.title) > 0)
                order by conversation.updated_at desc, conversation.conversation_id
                """)
                .param("workspaceId", workspaceId)
                .param("userId", userId)
                .param("query", titleQuery)
                .query(this::mapConversation)
                .list());
    }

    /** 按成员关系读取会话。无权访问时不暴露会话存在性。 */
    @Override
    public Optional<ConversationRecord> findConversation(UUID conversationId, String userId) {
        Objects.requireNonNull(conversationId, "conversationId 不能为空");
        requireText(userId, "userId");
        return jdbcClient.sql("""
                select conversation.conversation_id,
                       conversation.workspace_id,
                       conversation.created_by,
                       conversation.title,
                       conversation.status,
                       conversation.created_at,
                       conversation.updated_at
                from agent_conversations conversation
                join agent_workspace_members member
                  on member.workspace_id = conversation.workspace_id
                 and member.user_id = :userId
                join agent_users app_user on app_user.user_id = member.user_id
                where conversation.conversation_id = :conversationId
                  and app_user.enabled = true
                """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .query(this::mapConversation)
                .optional();
    }

    /** 创建一个 ACTIVE 会话。调用方应在服务层完成 OPERATOR 权限校验。 */
    @Override
    public ConversationRecord createConversation(
            UUID conversationId,
            UUID workspaceId,
            Actor actor,
            String title,
            Instant now) {
        Objects.requireNonNull(conversationId, "conversationId 不能为空");
        Objects.requireNonNull(workspaceId, "workspaceId 不能为空");
        Objects.requireNonNull(actor, "actor 不能为空");
        requireText(title, "title");
        Objects.requireNonNull(now, "now 不能为空");
        return requireResult(transactionTemplate.execute(status -> {
            if (findWorkspace(workspaceId, actor.userId()).isEmpty()) {
                throw new ConversationNotFoundException(conversationId);
            }
            ensureUser(actor, now);
            jdbcClient.sql("""
                    insert into agent_conversations (
                        conversation_id, workspace_id, created_by, title, status,
                        created_at, updated_at
                    ) values (
                        :conversationId, :workspaceId, :createdBy, :title, :status,
                        :createdAt, :updatedAt
                    )
                    """)
                    .param("conversationId", conversationId)
                    .param("workspaceId", workspaceId)
                    .param("createdBy", actor.userId())
                    .param("title", title)
                    .param("status", ConversationStatus.ACTIVE.name())
                    .param("createdAt", timestamp(now))
                    .param("updatedAt", timestamp(now))
                    .update();
            return findConversation(conversationId, actor.userId()).orElseThrow();
        }));
    }

    /** 归档会话；重复归档和不存在均视为冲突/不可见。 */
    @Override
    public ConversationRecord archiveConversation(UUID conversationId, String userId, Instant now) {
        Objects.requireNonNull(conversationId, "conversationId 不能为空");
        requireText(userId, "userId");
        Objects.requireNonNull(now, "now 不能为空");
        return requireResult(transactionTemplate.execute(status -> {
            ConversationRecord conversation = findConversationForUpdate(conversationId, userId)
                    .orElseThrow(() -> new ConversationNotFoundException(conversationId));
            if (conversation.status() == ConversationStatus.ARCHIVED) {
                throw new ConversationConflictException("会话已归档: " + conversationId);
            }
            jdbcClient.sql("""
                    update agent_conversations
                    set status = :status, updated_at = :updatedAt
                    where conversation_id = :conversationId
                    """)
                    .param("status", ConversationStatus.ARCHIVED.name())
                    .param("updatedAt", timestamp(now))
                    .param("conversationId", conversationId)
                    .update();
            return findConversation(conversationId, userId).orElseThrow();
        }));
    }

    /** 在成员权限范围内更新会话标题。 */
    @Override
    public ConversationRecord renameConversation(
            UUID conversationId,
            String userId,
            String title,
            Instant now) {
        Objects.requireNonNull(conversationId, "conversationId 不能为空");
        requireText(userId, "userId");
        requireText(title, "title");
        Objects.requireNonNull(now, "now 不能为空");
        return requireResult(transactionTemplate.execute(status -> {
            findConversationForUpdate(conversationId, userId)
                    .orElseThrow(() -> new ConversationNotFoundException(conversationId));
            jdbcClient.sql("""
                    update agent_conversations
                    set title = :title, updated_at = :updatedAt
                    where conversation_id = :conversationId
                    """)
                    .param("title", title)
                    .param("updatedAt", timestamp(now))
                    .param("conversationId", conversationId)
                    .update();
            return findConversation(conversationId, userId).orElseThrow();
        }));
    }

    /** 锁定会话并分配下一个轮次；活动轮次存在时返回冲突。 */
    @Override
    public ConversationTurnRecord createPendingTurn(
            UUID conversationId,
            String userId,
            String userContent,
            Instant now) {
        Objects.requireNonNull(conversationId, "conversationId 不能为空");
        requireText(userId, "userId");
        requireText(userContent, "userContent");
        Objects.requireNonNull(now, "now 不能为空");
        return requireResult(transactionTemplate.execute(status -> {
            ConversationRecord conversation = findConversationForUpdate(conversationId, userId)
                    .orElseThrow(() -> new ConversationNotFoundException(conversationId));
            if (conversation.status() != ConversationStatus.ACTIVE) {
                throw new ConversationConflictException("归档会话不能提交轮次: " + conversationId);
            }
            Long active = jdbcClient.sql("""
                    select count(*) from agent_conversation_turns
                    where conversation_id = :conversationId
                      and status in ('PENDING', 'RUNNING')
                    """)
                    .param("conversationId", conversationId)
                    .query(Long.class)
                    .single();
            if (active != 0) {
                throw new ConversationConflictException("会话已有活动轮次: " + conversationId);
            }
            Long nextIndex = jdbcClient.sql("""
                    select coalesce(max(turn_index), 0) + 1
                    from agent_conversation_turns
                    where conversation_id = :conversationId
                    """)
                    .param("conversationId", conversationId)
                    .query(Long.class)
                    .single();
            UUID turnId = UUID.randomUUID();
            jdbcClient.sql("""
                    insert into agent_conversation_turns (
                        turn_id, conversation_id, turn_index, user_content, status, created_at
                    ) values (
                        :turnId, :conversationId, :turnIndex, :userContent, :status, :createdAt
                    )
                    """)
                    .param("turnId", turnId)
                    .param("conversationId", conversationId)
                    .param("turnIndex", nextIndex)
                    .param("userContent", userContent)
                    .param("status", ConversationTurnStatus.PENDING.name())
                    .param("createdAt", timestamp(now))
                    .update();
            jdbcClient.sql("""
                    update agent_conversations set updated_at = :updatedAt
                    where conversation_id = :conversationId
                    """)
                    .param("updatedAt", timestamp(now))
                    .param("conversationId", conversationId)
                    .update();
            return findTurn(turnId, userId).orElseThrow();
        }));
    }

    @Override
    public Optional<ConversationTurnRecord> findTurn(UUID turnId, String userId) {
        Objects.requireNonNull(turnId, "turnId 不能为空");
        requireText(userId, "userId");
        return jdbcClient.sql(turnSelect() + """
                where turn.turn_id = :turnId and member.user_id = :userId
                  and app_user.enabled = true
                """)
                .param("turnId", turnId)
                .param("userId", userId)
                .query(this::mapTurn)
                .optional();
    }

    @Override
    public Optional<ConversationTurnRecord> findTurnByRunId(UUID runId, String userId) {
        Objects.requireNonNull(runId, "runId 不能为空");
        requireText(userId, "userId");
        return jdbcClient.sql(turnSelect() + """
                where turn.run_id = :runId and member.user_id = :userId
                  and app_user.enabled = true
                """)
                .param("runId", runId)
                .param("userId", userId)
                .query(this::mapTurn)
                .optional();
    }

    /** 按唯一 Run 标识反查轮次，供终态投影器在服务端使用。 */
    @Override
    public Optional<ConversationTurnRecord> findTurnByRunId(UUID runId) {
        Objects.requireNonNull(runId, "runId 不能为空");
        return jdbcClient.sql(turnSelect() + """
                where turn.run_id = :runId
                """)
                .param("runId", runId)
                .query(this::mapTurn)
                .optional();
    }

    @Override
    public List<ConversationTurnRecord> findTurns(UUID conversationId, String userId) {
        Objects.requireNonNull(conversationId, "conversationId 不能为空");
        requireText(userId, "userId");
        return List.copyOf(jdbcClient.sql(turnSelect() + """
                where turn.conversation_id = :conversationId and member.user_id = :userId
                  and app_user.enabled = true
                order by turn.turn_index
                """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .query(this::mapTurn)
                .list());
    }

    @Override
    public ConversationTurnRecord markTurnRunning(UUID turnId, UUID runId, Instant now) {
        Objects.requireNonNull(turnId, "turnId 不能为空");
        Objects.requireNonNull(runId, "runId 不能为空");
        Objects.requireNonNull(now, "now 不能为空");
        return requireResult(transactionTemplate.execute(status -> {
            ConversationTurnRecord turn = lockTurn(turnId)
                    .orElseThrow(() -> new ConversationTurnNotFoundException(turnId));
            if (turn.status() != ConversationTurnStatus.PENDING) {
                if (turn.status() == ConversationTurnStatus.RUNNING && runId.equals(turn.runId())) {
                    return turn;
                }
                throw new ConversationConflictException("轮次不能转为 RUNNING: " + turnId);
            }
            jdbcClient.sql("""
                    update agent_conversation_turns
                    set run_id = :runId, status = :status
                    where turn_id = :turnId
                    """)
                    .param("runId", runId)
                    .param("status", ConversationTurnStatus.RUNNING.name())
                    .param("turnId", turnId)
                    .update();
            return mapTurnById(turnId);
        }));
    }

    @Override
    public ConversationTurnRecord markTurnCompleted(UUID turnId, String assistantContent, Instant now) {
        Objects.requireNonNull(turnId, "turnId 不能为空");
        requireText(assistantContent, "assistantContent");
        Objects.requireNonNull(now, "now 不能为空");
        return requireResult(transactionTemplate.execute(status -> {
            ConversationTurnRecord turn = lockTurn(turnId)
                    .orElseThrow(() -> new ConversationTurnNotFoundException(turnId));
            if (turn.status() == ConversationTurnStatus.COMPLETED) {
                if (!assistantContent.equals(turn.assistantContent())) {
                    throw new ConversationConflictException("已完成轮次内容不一致: " + turnId);
                }
                return turn;
            }
            if (turn.status() == ConversationTurnStatus.FAILED) {
                throw new ConversationConflictException("失败轮次不能完成: " + turnId);
            }
            jdbcClient.sql("""
                    update agent_conversation_turns
                    set assistant_content = :assistantContent,
                        status = :status, completed_at = :completedAt
                    where turn_id = :turnId
                    """)
                    .param("assistantContent", assistantContent)
                    .param("status", ConversationTurnStatus.COMPLETED.name())
                    .param("completedAt", timestamp(now))
                    .param("turnId", turnId)
                    .update();
            touchConversation(turn.conversationId(), now);
            return mapTurnById(turnId);
        }));
    }

    @Override
    public ConversationTurnRecord markTurnFailed(UUID turnId, String error, Instant now) {
        Objects.requireNonNull(turnId, "turnId 不能为空");
        requireText(error, "error");
        Objects.requireNonNull(now, "now 不能为空");
        return requireResult(transactionTemplate.execute(status -> {
            ConversationTurnRecord turn = lockTurn(turnId)
                    .orElseThrow(() -> new ConversationTurnNotFoundException(turnId));
            if (turn.status() == ConversationTurnStatus.FAILED) {
                if (!error.equals(turn.error())) {
                    throw new ConversationConflictException("已失败轮次错误不一致: " + turnId);
                }
                return turn;
            }
            if (turn.status() == ConversationTurnStatus.COMPLETED) {
                throw new ConversationConflictException("已完成轮次不能失败: " + turnId);
            }
            jdbcClient.sql("""
                    update agent_conversation_turns
                    set status = :status, error = :error, completed_at = :completedAt
                    where turn_id = :turnId
                    """)
                    .param("status", ConversationTurnStatus.FAILED.name())
                    .param("error", error)
                    .param("completedAt", timestamp(now))
                    .param("turnId", turnId)
                    .update();
            touchConversation(turn.conversationId(), now);
            return mapTurnById(turnId);
        }));
    }

    private Optional<ConversationRecord> findConversationForUpdate(UUID conversationId, String userId) {
        return jdbcClient.sql("""
                select conversation.conversation_id,
                       conversation.workspace_id,
                       conversation.created_by,
                       conversation.title,
                       conversation.status,
                       conversation.created_at,
                       conversation.updated_at
                from agent_conversations conversation
                join agent_workspace_members member
                  on member.workspace_id = conversation.workspace_id
                 and member.user_id = :userId
                join agent_users app_user on app_user.user_id = member.user_id
                where conversation.conversation_id = :conversationId
                  and app_user.enabled = true
                for update of conversation
                """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .query(this::mapConversation)
                .optional();
    }

    private Optional<ConversationTurnRecord> lockTurn(UUID turnId) {
        return jdbcClient.sql("""
                select turn_id, conversation_id, turn_index, user_content,
                       assistant_content, run_id, status, error, created_at, completed_at
                from agent_conversation_turns
                where turn_id = :turnId
                for update
                """)
                .param("turnId", turnId)
                .query(this::mapTurn)
                .optional();
    }

    private ConversationTurnRecord mapTurnById(UUID turnId) {
        return jdbcClient.sql("""
                select turn_id, conversation_id, turn_index, user_content,
                       assistant_content, run_id, status, error, created_at, completed_at
                from agent_conversation_turns
                where turn_id = :turnId
                """)
                .param("turnId", turnId)
                .query(this::mapTurn)
                .single();
    }

    private void touchConversation(UUID conversationId, Instant now) {
        jdbcClient.sql("""
                update agent_conversations set updated_at = :updatedAt
                where conversation_id = :conversationId
                """)
                .param("updatedAt", timestamp(now))
                .param("conversationId", conversationId)
                .update();
    }

    private String turnSelect() {
        return """
                select turn.turn_id, turn.conversation_id, turn.turn_index, turn.user_content,
                       turn.assistant_content, turn.run_id, turn.status, turn.error,
                       turn.created_at, turn.completed_at
                from agent_conversation_turns turn
                join agent_conversations conversation on conversation.conversation_id = turn.conversation_id
                join agent_workspace_members member on member.workspace_id = conversation.workspace_id
                join agent_users app_user on app_user.user_id = member.user_id
                """;
    }

    private ConversationRecord mapConversation(java.sql.ResultSet resultSet, int rowNumber)
            throws java.sql.SQLException {
        return new ConversationRecord(
                resultSet.getObject("conversation_id", UUID.class),
                resultSet.getObject("workspace_id", UUID.class),
                resultSet.getString("created_by"),
                resultSet.getString("title"),
                ConversationStatus.valueOf(resultSet.getString("status")),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private ConversationTurnRecord mapTurn(java.sql.ResultSet resultSet, int rowNumber)
            throws java.sql.SQLException {
        return new ConversationTurnRecord(
                resultSet.getObject("turn_id", UUID.class),
                resultSet.getObject("conversation_id", UUID.class),
                resultSet.getLong("turn_index"),
                resultSet.getString("user_content"),
                resultSet.getString("assistant_content"),
                resultSet.getObject("run_id", UUID.class),
                ConversationTurnStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("error"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("completed_at") == null
                        ? null : resultSet.getTimestamp("completed_at").toInstant());
    }

    /** 会话不可见或不存在。 */
    public static final class ConversationNotFoundException extends RuntimeException {
        public ConversationNotFoundException(UUID conversationId) {
            super("会话不存在或当前用户无权访问: " + conversationId);
        }
    }

    /** 会话或轮次当前状态不允许执行操作。 */
    public static final class ConversationConflictException extends RuntimeException {
        public ConversationConflictException(String message) {
            super(message);
        }
    }

    /** 轮次不可见或不存在。 */
    public static final class ConversationTurnNotFoundException extends RuntimeException {
        public ConversationTurnNotFoundException(UUID turnId) {
            super("会话轮次不存在: " + turnId);
        }
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
