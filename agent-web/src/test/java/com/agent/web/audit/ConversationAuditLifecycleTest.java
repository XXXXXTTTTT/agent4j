package com.agent.web.audit;

import com.agent.core.conversation.ConversationContext;
import com.agent.core.engine.AgentState;
import com.agent.core.engine.RunCheckpoint;
import com.agent.core.engine.RunStatus;
import com.agent.core.trace.TraceEvent;
import com.agent.web.conversation.ConversationRecord;
import com.agent.web.conversation.ConversationRepository;
import com.agent.web.conversation.ConversationRunProjector;
import com.agent.web.conversation.ConversationService;
import com.agent.web.conversation.ConversationStatus;
import com.agent.web.conversation.ConversationTurnRecord;
import com.agent.web.conversation.ConversationTurnStatus;
import com.agent.web.identity.Actor;
import com.agent.web.workspace.WorkspaceAccessService;
import com.agent.web.workspace.WorkspacePermission;
import com.agent.web.workspace.WorkspaceRecord;
import com.agent.web.workspace.WorkspaceRepository;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationAuditLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");
    private static final UUID WORKSPACE_ID = UUID.randomUUID();
    private static final UUID CONVERSATION_ID = UUID.randomUUID();
    private static final UUID TURN_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final Actor ACTOR = new Actor("local", "本地用户");

    @Test
    void auditsSubmittedStartedAndCompletedConversationContent() {
        FakeRepository repository = new FakeRepository();
        CapturingAuditSink audit = new CapturingAuditSink();
        ConversationService service = service(repository, audit);

        service.submitTurn(CONVERSATION_ID, "用户问题", "");

        ConversationRunProjector projector = new ConversationRunProjector(
                repository, audit, Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC));
        AgentState terminal = AgentState.empty()
                .withVariable("planner.userId", ACTOR.userId())
                .withVariable("conversation.workspaceId", WORKSPACE_ID.toString())
                .withVariable("final_response", "Agent 回答");
        projector.publish(new TraceEvent.Completed(
                UUID.randomUUID(), RUN_ID, 2, NOW.plusSeconds(2)), terminal);

        assertThat(audit.events)
                .extracting(ConversationAuditEvent::eventType)
                .containsExactly(
                        ConversationAuditEventType.CONVERSATION_TURN_SUBMITTED,
                        ConversationAuditEventType.CONVERSATION_TURN_STARTED,
                        ConversationAuditEventType.CONVERSATION_TURN_COMPLETED);
        ConversationAuditEvent completed = audit.events.getLast();
        assertThat(completed.userId()).isEqualTo("local");
        assertThat(completed.workspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(completed.userContent()).isEqualTo("用户问题");
        assertThat(completed.assistantContent()).isEqualTo("Agent 回答");
        assertThat(completed.durationMs()).isEqualTo(2000L);
    }

    @Test
    void auditsStartFailureWithUserContentAndError() {
        FakeRepository repository = new FakeRepository();
        CapturingAuditSink audit = new CapturingAuditSink();
        ConversationService service = new ConversationService(
                repository,
                access(repository),
                (conversationId, userId, maxTurns, maxCharacters) ->
                        new ConversationContext(List.of(), 0, false),
                () -> ACTOR,
                (graphId, state, beforeDispatch) -> {
                    throw new IllegalStateException("启动失败");
                },
                null,
                audit,
                Clock.fixed(NOW, ZoneOffset.UTC));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.submitTurn(CONVERSATION_ID, "失败问题", ""))
                .isInstanceOf(IllegalStateException.class);

        assertThat(audit.events)
                .extracting(ConversationAuditEvent::eventType)
                .containsExactly(
                        ConversationAuditEventType.CONVERSATION_TURN_SUBMITTED,
                        ConversationAuditEventType.CONVERSATION_TURN_FAILED);
        assertThat(audit.events.getLast().userContent()).isEqualTo("失败问题");
        assertThat(audit.events.getLast().error()).contains("启动失败");
    }

    @Test
    void auditsConversationCreationAndArchive() {
        FakeRepository repository = new FakeRepository();
        CapturingAuditSink audit = new CapturingAuditSink();
        ConversationService service = service(repository, audit);

        ConversationRecord created = service.createConversation(WORKSPACE_ID);
        ConversationRecord archived = service.archive(created.conversationId());

        assertThat(archived.status()).isEqualTo(ConversationStatus.ARCHIVED);
        assertThat(audit.events)
                .extracting(ConversationAuditEvent::eventType)
                .containsExactly(
                        ConversationAuditEventType.CONVERSATION_CREATED,
                        ConversationAuditEventType.CONVERSATION_ARCHIVED);
    }

    @Test
    void auditFailureDoesNotChangeConversationOrTerminalRunSemantics() {
        FakeRepository repository = new FakeRepository();
        ConversationAuditSink failingAudit = event -> {
            throw new IllegalStateException("audit unavailable");
        };
        ConversationTurnRecord running = service(repository, failingAudit)
                .submitTurn(CONVERSATION_ID, "用户问题", "");

        ConversationRunProjector projector = new ConversationRunProjector(
                repository, failingAudit, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
        projector.publish(new TraceEvent.Completed(
                UUID.randomUUID(), running.runId(), 2, NOW.plusSeconds(1)),
                AgentState.empty().withVariable("final_response", "Agent 回答"));

        assertThat(repository.turn.status()).isEqualTo(ConversationTurnStatus.COMPLETED);
        assertThat(repository.turn.assistantContent()).isEqualTo("Agent 回答");
    }

    @Test
    void writesOneTerminalAuditEventForConcurrentDuplicateNotifications() throws Exception {
        FakeRepository repository = new FakeRepository();
        service(repository, ConversationAuditSink.noop())
                .submitTurn(CONVERSATION_ID, "并发问题", "");
        CapturingAuditSink audit = new CapturingAuditSink();
        ConversationRunProjector projector = new ConversationRunProjector(
                repository, audit, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
        TraceEvent.Completed completed = new TraceEvent.Completed(
                UUID.randomUUID(), RUN_ID, 2, NOW.plusSeconds(1));
        AgentState state = AgentState.empty().withVariable("final_response", "唯一回答");

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 20)
                    .mapToObj(index -> (java.util.concurrent.Callable<Void>) () -> {
                        projector.publish(completed, state);
                        return null;
                    })
                    .toList();
            for (var future : executor.invokeAll(tasks)) {
                future.get();
            }
        }

        assertThat(repository.completionCalls.get()).isEqualTo(1);
        assertThat(audit.events)
                .extracting(ConversationAuditEvent::eventType)
                .containsExactly(ConversationAuditEventType.CONVERSATION_TURN_COMPLETED);
    }

    private static ConversationService service(FakeRepository repository, ConversationAuditSink audit) {
        return new ConversationService(
                repository,
                access(repository),
                (conversationId, userId, maxTurns, maxCharacters) ->
                        new ConversationContext(List.of(), 0, false),
                () -> ACTOR,
                (graphId, state, beforeDispatch) -> {
                    RunCheckpoint checkpoint = new RunCheckpoint(
                            RUN_ID, 0, graphId, RunStatus.RUNNING, state,
                            "planner", null, null, null, null, NOW);
                    beforeDispatch.accept(checkpoint);
                    return checkpoint;
                },
                null,
                audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static WorkspaceAccessService access(WorkspaceRepository repository) {
        return new WorkspaceAccessService(repository, Path.of("D:/agent4j"),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class CapturingAuditSink implements ConversationAuditSink {
        private final List<ConversationAuditEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void record(ConversationAuditEvent event) {
            events.add(event);
        }
    }

    private static final class FakeRepository implements ConversationRepository, WorkspaceRepository {
        private ConversationTurnRecord turn;
        private final AtomicInteger completionCalls = new AtomicInteger();

        @Override
        public ConversationRecord createConversation(
                UUID conversationId, UUID workspaceId, Actor actor, String title, Instant now) {
            return new ConversationRecord(
                    CONVERSATION_ID, WORKSPACE_ID, actor.userId(), title,
                    ConversationStatus.ACTIVE, now, now);
        }

        @Override
        public ConversationRecord archiveConversation(UUID conversationId, String userId, Instant now) {
            return new ConversationRecord(
                    CONVERSATION_ID, WORKSPACE_ID, userId, "会话",
                    ConversationStatus.ARCHIVED, NOW, now);
        }

        @Override
        public Optional<ConversationRecord> findConversation(UUID conversationId, String userId) {
            return Optional.of(new ConversationRecord(
                    CONVERSATION_ID, WORKSPACE_ID, ACTOR.userId(), "会话",
                    ConversationStatus.ACTIVE, NOW, NOW));
        }

        @Override
        public ConversationTurnRecord createPendingTurn(
                UUID conversationId, String userId, String userContent, Instant now) {
            turn = new ConversationTurnRecord(
                    TURN_ID, CONVERSATION_ID, 1, userContent, null, null,
                    ConversationTurnStatus.PENDING, null, NOW, null);
            return turn;
        }

        @Override
        public ConversationRecord renameConversation(
                UUID conversationId, String userId, String title, Instant now) {
            return findConversation(conversationId, userId).orElseThrow();
        }

        @Override
        public ConversationTurnRecord markTurnRunning(UUID turnId, UUID runId, Instant now) {
            turn = new ConversationTurnRecord(
                    TURN_ID, CONVERSATION_ID, 1, turn.userContent(), null, runId,
                    ConversationTurnStatus.RUNNING, null, NOW, null);
            return turn;
        }

        @Override
        public Optional<ConversationTurnRecord> findTurnByRunId(UUID runId) {
            return Optional.ofNullable(turn);
        }

        @Override
        public ConversationTurnRecord markTurnCompleted(
                UUID turnId, String assistantContent, Instant now) {
            completionCalls.incrementAndGet();
            turn = new ConversationTurnRecord(
                    TURN_ID, CONVERSATION_ID, 1, turn.userContent(), assistantContent, RUN_ID,
                    ConversationTurnStatus.COMPLETED, null, NOW, now);
            return turn;
        }

        @Override
        public ConversationTurnRecord markTurnFailed(UUID turnId, String error, Instant now) {
            turn = new ConversationTurnRecord(
                    TURN_ID, CONVERSATION_ID, 1, turn.userContent(), null, turn.runId(),
                    ConversationTurnStatus.FAILED, error, NOW, now);
            return turn;
        }

        @Override
        public List<ConversationTurnRecord> findTurns(UUID conversationId, String userId) {
            return turn == null ? List.of() : List.of(turn);
        }

        @Override
        public Optional<WorkspaceRecord> findWorkspace(UUID workspaceId, String userId) {
            return Optional.of(new WorkspaceRecord(
                    WORKSPACE_ID, ACTOR.userId(), "工作区", Path.of("D:/agent4j"),
                    "local", WorkspacePermission.OPERATOR, NOW, NOW));
        }

        @Override
        public List<WorkspaceRecord> findWorkspaces(String userId) {
            return List.of();
        }

        @Override
        public WorkspaceRecord createWorkspace(
                UUID workspaceId, Actor owner, String displayName, Path workspacePath,
                String repositoryId, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorkspaceRecord ensureDefaultWorkspace(
                UUID workspaceId, Actor owner, String displayName, Path workspacePath,
                String repositoryId, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void ensureUser(Actor actor, Instant now) {
        }

        @Override
        public void grantMember(
                UUID workspaceId, String userId, WorkspacePermission permission, Instant now) {
        }
    }
}
