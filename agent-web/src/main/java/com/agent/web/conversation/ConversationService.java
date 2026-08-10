package com.agent.web.conversation;

import com.agent.core.conversation.ConversationContext;
import com.agent.core.conversation.ConversationContextProvider;
import com.agent.core.engine.AgentState;
import com.agent.core.engine.RunCheckpoint;
import com.agent.web.audit.ConversationAuditEvent;
import com.agent.web.audit.ConversationAuditEventType;
import com.agent.web.audit.ConversationAuditSink;
import com.agent.web.identity.Actor;
import com.agent.web.identity.ActorResolver;
import com.agent.web.workspace.WorkspaceAccessService;
import com.agent.web.workspace.WorkspacePermission;
import com.agent.web.workspace.WorkspaceRecord;
import com.agent.web.validation.ReviewerUrlValidator;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 绑定用户和工作区的会话应用服务。 */
public final class ConversationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConversationService.class);

    private static final int CONTEXT_MAX_TURNS = 20;
    private static final int CONTEXT_MAX_CHARACTERS = 32_000;
    private static final String GRAPH_ID = "code-agent";

    private final ConversationRepository repository;
    private final WorkspaceAccessService workspaceAccess;
    private final ConversationContextProvider contextProvider;
    private final ActorResolver actorResolver;
    private final ConversationRunStarter runStarter;
    private final ConversationRunProjector conversationProjector;
    private final ConversationAuditSink auditSink;
    private final Clock clock;

    /** 创建会话应用服务。 */
    public ConversationService(
            ConversationRepository repository,
            WorkspaceAccessService workspaceAccess,
            ConversationContextProvider contextProvider,
            ActorResolver actorResolver,
            ConversationRunStarter runStarter,
            Clock clock) {
        this(repository, workspaceAccess, contextProvider, actorResolver, runStarter,
                null, ConversationAuditSink.noop(), clock);
    }

    /** 创建带终态对账能力的会话应用服务。 */
    public ConversationService(
            ConversationRepository repository,
            WorkspaceAccessService workspaceAccess,
            ConversationContextProvider contextProvider,
            ActorResolver actorResolver,
            ConversationRunStarter runStarter,
            ConversationRunProjector conversationProjector,
            Clock clock) {
        this(repository, workspaceAccess, contextProvider, actorResolver, runStarter,
                conversationProjector, ConversationAuditSink.noop(), clock);
    }

    /** 创建带终态对账和业务审计能力的会话应用服务。 */
    public ConversationService(
            ConversationRepository repository,
            WorkspaceAccessService workspaceAccess,
            ConversationContextProvider contextProvider,
            ActorResolver actorResolver,
            ConversationRunStarter runStarter,
            ConversationRunProjector conversationProjector,
            ConversationAuditSink auditSink,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.workspaceAccess = Objects.requireNonNull(workspaceAccess, "workspaceAccess 不能为空");
        this.contextProvider = Objects.requireNonNull(contextProvider, "contextProvider 不能为空");
        this.actorResolver = Objects.requireNonNull(actorResolver, "actorResolver 不能为空");
        this.runStarter = Objects.requireNonNull(runStarter, "runStarter 不能为空");
        this.conversationProjector = conversationProjector;
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /** 创建绑定当前用户的空会话。 */
    public ConversationRecord createConversation(UUID workspaceId) {
        Actor actor = actorResolver.current();
        WorkspaceRecord workspace = workspaceAccess.requireWorkspace(
                Objects.requireNonNull(workspaceId, "workspaceId 不能为空"),
                actor.userId(),
                WorkspacePermission.OPERATOR);
        ConversationRecord created = repository.createConversation(
                UUID.randomUUID(), workspace.workspaceId(), actor, "新建会话", clock.instant());
        audit(new ConversationAuditEvent(
                ConversationAuditEventType.CONVERSATION_CREATED,
                clock.instant(), actor.userId(), workspace.workspaceId(),
                created.conversationId(), null, null, null,
                created.status().name(), null, null, null, null));
        return created;
    }

    /** 查询当前用户可见的工作区会话。 */
    public List<ConversationRecord> listConversations(UUID workspaceId, String query) {
        Actor actor = actorResolver.current();
        WorkspaceRecord workspace = workspaceAccess.requireWorkspace(
                workspaceId, actor.userId(), WorkspacePermission.VIEWER);
        return repository.findConversations(workspace.workspaceId(), actor.userId(), query);
    }

    /** 读取当前用户可见的会话。 */
    public ConversationRecord getConversation(UUID conversationId) {
        Actor actor = actorResolver.current();
        return repository.findConversation(conversationId, actor.userId())
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));
    }

    /** 读取当前用户可见的完整轮次列表。 */
    public List<ConversationTurnRecord> listTurns(UUID conversationId) {
        Actor actor = actorResolver.current();
        getConversation(conversationId);
        return reconcileTurns(conversationId, actor.userId());
    }

    private List<ConversationTurnRecord> reconcileTurns(UUID conversationId, String userId) {
        List<ConversationTurnRecord> turns = repository.findTurns(conversationId, userId);
        if (conversationProjector == null) {
            return turns;
        }
        boolean hasActiveRun = turns.stream()
                .anyMatch(turn -> turn.status() == ConversationTurnStatus.RUNNING
                        && turn.runId() != null);
        if (!hasActiveRun) {
            return turns;
        }
        turns.forEach(conversationProjector::reconcile);
        return repository.findTurns(conversationId, userId);
    }

    /** 提交一轮任务，创建独立 Run 并把其身份绑定到当前用户和工作区。 */
    public ConversationTurnRecord submitTurn(
            UUID conversationId,
            String content,
            String reviewerUrl) {
        Actor actor = actorResolver.current();
        requireText(content, "content");
        String exactReviewerUrl = ReviewerUrlValidator.validateOptional(reviewerUrl);
        ConversationRecord conversation = repository.findConversation(conversationId, actor.userId())
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));
        WorkspaceRecord workspace = workspaceAccess.requireWorkspace(
                conversation.workspaceId(), actor.userId(), WorkspacePermission.OPERATOR);
        reconcileTurns(conversation.conversationId(), actor.userId());
        ConversationContext context = contextProvider.load(
                conversation.conversationId(), actor.userId(),
                CONTEXT_MAX_TURNS, CONTEXT_MAX_CHARACTERS);
        ConversationTurnRecord pending = repository.createPendingTurn(
                conversation.conversationId(), actor.userId(), content, clock.instant());
        audit(turnEvent(
                ConversationAuditEventType.CONVERSATION_TURN_SUBMITTED,
                actor.userId(), workspace.workspaceId(), pending, null, null));
        try {
            if (pending.turnIndex() == 1) {
                repository.renameConversation(
                        conversation.conversationId(), actor.userId(),
                        JdbcConversationRepositoryTitle.derive(content), clock.instant());
            }
            AgentState state = new AgentState(
                    context.messages(),
                    Map.of(
                            "planner.task", content,
                            "planner.repositoryId", workspace.repositoryId(),
                            "planner.userId", actor.userId(),
                            "coder.workspacePath", workspace.workspacePath().toString(),
                            "conversation.id", conversation.conversationId().toString(),
                            "conversation.workspaceId", workspace.workspaceId().toString(),
                            "conversation.turnId", pending.turnId().toString()),
                    List.of());
            if (!exactReviewerUrl.isBlank()) {
                state = state.withVariable("reviewer.url", exactReviewerUrl);
            }
            AtomicReference<ConversationTurnRecord> running = new AtomicReference<>();
            runStarter.start(GRAPH_ID, state, checkpoint -> {
                ConversationTurnRecord started = repository.markTurnRunning(
                        pending.turnId(), checkpoint.runId(), clock.instant());
                running.set(started);
                audit(turnEvent(
                        ConversationAuditEventType.CONVERSATION_TURN_STARTED,
                        actor.userId(), workspace.workspaceId(), started, null, null));
            });
            return Objects.requireNonNull(running.get(), "Run 启动前未绑定会话轮次");
        } catch (RuntimeException exception) {
            String error = stackTrace(exception);
            try {
                ConversationTurnRecord failed = repository.markTurnFailed(
                        pending.turnId(), error, clock.instant());
                audit(turnEvent(
                        ConversationAuditEventType.CONVERSATION_TURN_FAILED,
                        actor.userId(), workspace.workspaceId(), failed, null, error));
            } catch (RuntimeException persistenceFailure) {
                exception.addSuppressed(persistenceFailure);
            }
            throw exception;
        }
    }

    /** 归档当前用户可操作的会话。 */
    public ConversationRecord archive(UUID conversationId) {
        Actor actor = actorResolver.current();
        ConversationRecord conversation = getConversation(conversationId);
        workspaceAccess.requireWorkspace(
                conversation.workspaceId(), actor.userId(), WorkspacePermission.OPERATOR);
        ConversationRecord archived = repository.archiveConversation(
                conversationId, actor.userId(), clock.instant());
        audit(new ConversationAuditEvent(
                ConversationAuditEventType.CONVERSATION_ARCHIVED,
                clock.instant(), actor.userId(), archived.workspaceId(),
                archived.conversationId(), null, null, null,
                archived.status().name(), null, null, null, null));
        return archived;
    }

    private ConversationAuditEvent turnEvent(
            ConversationAuditEventType eventType,
            String userId,
            UUID workspaceId,
            ConversationTurnRecord turn,
            String assistantContent,
            String error) {
        return new ConversationAuditEvent(
                eventType,
                clock.instant(),
                userId,
                workspaceId,
                turn.conversationId(),
                turn.turnId(),
                turn.runId(),
                turn.turnIndex(),
                turn.status().name(),
                turn.userContent(),
                assistantContent,
                error,
                durationMillis(turn));
    }

    private long durationMillis(ConversationTurnRecord turn) {
        return Math.max(0L, java.time.Duration.between(
                turn.createdAt(), clock.instant()).toMillis());
    }

    private void audit(ConversationAuditEvent event) {
        try {
            auditSink.record(event);
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Conversation audit write failed eventType={} conversationId={} turnId={} runId={}",
                    event.eventType(), event.conversationId(), event.turnId(), event.runId(), exception);
        }
    }

    private static String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }

    /** 会话不可见或不存在。 */
    public static final class ConversationNotFoundException extends RuntimeException {
        public ConversationNotFoundException(UUID conversationId) {
            super("会话不存在或当前用户无权访问: " + conversationId);
        }
    }

    /** 与仓储共享标题规范，避免 Web 服务依赖 JDBC 实现类型。 */
    static final class JdbcConversationRepositoryTitle {
        private JdbcConversationRepositoryTitle() {
        }

        static String derive(String content) {
            String normalized = content.strip().replaceAll("\\s+", " ");
            int count = normalized.codePointCount(0, normalized.length());
            return count <= 80 ? normalized : normalized.substring(0, normalized.offsetByCodePoints(0, 80));
        }
    }
}
