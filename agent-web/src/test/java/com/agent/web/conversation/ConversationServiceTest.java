package com.agent.web.conversation;

import com.agent.core.conversation.ConversationContext;
import com.agent.core.conversation.ConversationContextProvider;
import com.agent.core.engine.AgentState;
import com.agent.core.engine.Checkpointer;
import com.agent.core.engine.CheckpointAppend;
import com.agent.core.engine.RunCheckpoint;
import com.agent.core.engine.RunStatus;
import com.agent.core.llm.ChatMessage;
import com.agent.core.skill.SkillCatalogProvider;
import com.agent.core.skill.SkillCatalogSnapshot;
import com.agent.core.skill.SkillCatalogSnapshotCodec;
import com.agent.core.mcp.McpCatalogProvider;
import com.agent.core.mcp.McpCatalogSnapshot;
import com.agent.core.mcp.McpCatalogSnapshotCodec;
import com.agent.core.orchestration.AgentRole;
import com.agent.core.orchestration.OrchestrationMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agent.web.identity.Actor;
import com.agent.web.identity.ActorResolver;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationServiceTest {

    private static final UUID WORKSPACE_ID = UUID.randomUUID();
    private static final UUID CONVERSATION_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-07T00:00:00Z");

    @Test
    void submissionUsesResolvedActorWorkspaceAndPersistedHistory() {
        Actor resolved = new Actor("resolved-user", "Resolved");
        FakeConversationRepository repository = new FakeConversationRepository();
        repository.conversation = new ConversationRecord(
                CONVERSATION_ID, WORKSPACE_ID, resolved.userId(), "标题",
                ConversationStatus.ACTIVE, NOW, NOW);
        repository.pending = new ConversationTurnRecord(
                UUID.randomUUID(), CONVERSATION_ID, 2, "当前问题", null, null,
                ConversationTurnStatus.PENDING, null, NOW, null);
        CapturingStarter starter = new CapturingStarter();
        ConversationContextProvider contextProvider = (id, userId, maxTurns, maxCharacters) ->
                new ConversationContext(
                        List.of(ChatMessage.user("历史问题"), ChatMessage.assistant("历史回答")),
                        1,
                        false);
        WorkspaceRepository workspaceRepository = new WorkspaceRepository() {
            @Override
            public Optional<WorkspaceRecord> findWorkspace(UUID workspaceId, String userId) {
                return workspaceId.equals(WORKSPACE_ID) && userId.equals(resolved.userId())
                        ? Optional.of(new WorkspaceRecord(
                                WORKSPACE_ID, resolved.userId(), "工作区", Path.of("D:/agent4j"),
                                "repo-exact", WorkspacePermission.OPERATOR, NOW, NOW))
                        : Optional.empty();
            }

            @Override
            public List<WorkspaceRecord> findWorkspaces(String userId) {
                return List.of();
            }

            @Override
            public WorkspaceRecord createWorkspace(UUID workspaceId, Actor owner, String displayName,
                                                    Path workspacePath, String repositoryId, Instant now) {
                throw new UnsupportedOperationException();
            }

            @Override
            public WorkspaceRecord ensureDefaultWorkspace(UUID workspaceId, Actor owner, String displayName,
                                                           Path workspacePath, String repositoryId, Instant now) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void ensureUser(Actor actor, Instant now) {
            }

            @Override
            public void grantMember(UUID workspaceId, String userId, WorkspacePermission permission, Instant now) {
            }
        };
        WorkspaceAccessService access = new WorkspaceAccessService(
                workspaceRepository, Path.of("D:/agent4j"), Clock.fixed(NOW, ZoneOffset.UTC));
        ConversationService service = new ConversationService(
                repository, access, contextProvider, () -> resolved, starter,
                Clock.fixed(NOW, ZoneOffset.UTC));

        ConversationTurnRecord result = service.submitTurn(
                CONVERSATION_ID,
                "当前问题",
                "",
                "group-primary",
                OrchestrationMode.PARALLEL_RESEARCH,
                Map.of(AgentRole.RESEARCHER, "group-research"));

        assertThat(result).isEqualTo(repository.running);
        assertThat(starter.state.messages())
                .extracting(message -> ((ChatMessage.TextContent) message.content()).text())
                .containsExactly("历史问题", "历史回答");
        assertThat(starter.state.variables())
                .containsEntry("planner.task", "当前问题")
                .containsEntry("planner.repositoryId", "repo-exact")
                .containsEntry("planner.userId", "resolved-user")
                .containsEntry("coder.workspacePath", Path.of("D:/agent4j").toString())
                .containsEntry("conversation.id", CONVERSATION_ID.toString())
                .containsEntry("conversation.turnId", repository.pending.turnId().toString())
                .containsEntry(ConversationService.ORCHESTRATION_MODE_KEY, "PARALLEL_RESEARCH")
                .containsEntry(ConversationService.ORCHESTRATION_MODEL_GROUP_KEY_PREFIX + "COORDINATOR", "group-primary")
                .containsEntry(ConversationService.ORCHESTRATION_MODEL_GROUP_KEY_PREFIX + "RESEARCHER", "group-research")
                .containsEntry(ConversationService.ORCHESTRATION_MODEL_GROUP_KEY_PREFIX + "IMPLEMENTER", "group-primary")
                .containsEntry(ConversationService.ORCHESTRATION_MODEL_GROUP_KEY_PREFIX + "VERIFIER", "group-primary");
        assertThat(starter.state.variables()).doesNotContainKey("request.userId");
    }

    @Test
    void submissionFreezesSkillCatalogForResolvedActorAndWorkspace() {
        Actor resolved = new Actor("skill-user", "Skill");
        FakeConversationRepository repository = new FakeConversationRepository();
        repository.conversation = new ConversationRecord(
                CONVERSATION_ID, WORKSPACE_ID, resolved.userId(), "标题",
                ConversationStatus.ACTIVE, NOW, NOW);
        repository.pending = new ConversationTurnRecord(
                UUID.randomUUID(), CONVERSATION_ID, 1, "使用 Skill", null, null,
                ConversationTurnStatus.PENDING, null, NOW, null);
        CapturingStarter starter = new CapturingStarter();
        SkillCatalogProvider provider = (actorUserId, workspaceId) -> {
            assertThat(actorUserId).isEqualTo(resolved.userId());
            assertThat(workspaceId).isEqualTo(WORKSPACE_ID);
            return new SkillCatalogSnapshot(1, actorUserId, workspaceId, NOW, 0, List.of(), "");
        };
        ObjectMapper objectMapper = new ObjectMapper();
        ConversationService service = new ConversationService(
                repository,
                new WorkspaceAccessService(
                        new TestWorkspaceRepository(resolved), Path.of("D:/agent4j"),
                        Clock.fixed(NOW, ZoneOffset.UTC)),
                (id, userId, maxTurns, maxCharacters) -> new ConversationContext(List.of(), 0, false),
                () -> resolved, starter, null, com.agent.web.audit.ConversationAuditSink.noop(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                provider,
                new SkillCatalogSnapshotCodec(objectMapper));

        service.submitTurn(CONVERSATION_ID, "使用 Skill", "");

        String encoded = starter.state.variables().get("skill.catalogSnapshot");
        assertThat(encoded).isNotBlank();
        assertThat(new SkillCatalogSnapshotCodec(objectMapper)
                .decode(encoded, resolved.userId(), WORKSPACE_ID, new com.agent.core.tool.DefaultToolRegistry()))
                .extracting(SkillCatalogSnapshot::workspaceId).isEqualTo(WORKSPACE_ID);
    }

    @Test
    void submissionFreezesMcpCatalogForResolvedActorAndWorkspace() {
        Actor resolved = new Actor("mcp-user", "Mcp");
        FakeConversationRepository repository = new FakeConversationRepository();
        repository.conversation = new ConversationRecord(
                CONVERSATION_ID, WORKSPACE_ID, resolved.userId(), "标题",
                ConversationStatus.ACTIVE, NOW, NOW);
        repository.pending = new ConversationTurnRecord(
                UUID.randomUUID(), CONVERSATION_ID, 1, "使用 MCP", null, null,
                ConversationTurnStatus.PENDING, null, NOW, null);
        CapturingStarter starter = new CapturingStarter();
        McpCatalogProvider provider = (actorUserId, workspaceId) -> {
            assertThat(actorUserId).isEqualTo(resolved.userId());
            assertThat(workspaceId).isEqualTo(WORKSPACE_ID);
            return new McpCatalogSnapshot(1, actorUserId, workspaceId, NOW, List.of(), "");
        };
        ObjectMapper objectMapper = new ObjectMapper();
        ConversationService service = new ConversationService(
                repository,
                new WorkspaceAccessService(new TestWorkspaceRepository(resolved), Path.of("D:/agent4j"),
                        Clock.fixed(NOW, ZoneOffset.UTC)),
                (id, userId, maxTurns, maxCharacters) -> new ConversationContext(List.of(), 0, false),
                () -> resolved, starter, null, com.agent.web.audit.ConversationAuditSink.noop(),
                Clock.fixed(NOW, ZoneOffset.UTC), null,
                new SkillCatalogSnapshotCodec(objectMapper), provider,
                new McpCatalogSnapshotCodec(objectMapper));

        service.submitTurn(CONVERSATION_ID, "使用 MCP", "");

        String encoded = starter.state.variables().get("mcp.catalogSnapshot");
        assertThat(new McpCatalogSnapshotCodec(objectMapper)
                .decode(encoded, resolved.userId(), WORKSPACE_ID))
                .extracting(McpCatalogSnapshot::workspaceId).isEqualTo(WORKSPACE_ID);
    }

    @Test
    void persistsFullStartFailureOnPendingTurn() {
        Actor resolved = new Actor("failed-user", "Failed");
        FakeConversationRepository repository = new FakeConversationRepository();
        repository.conversation = new ConversationRecord(
                CONVERSATION_ID, WORKSPACE_ID, resolved.userId(), "标题",
                ConversationStatus.ACTIVE, NOW, NOW);
        repository.pending = new ConversationTurnRecord(
                UUID.randomUUID(), CONVERSATION_ID, 1, "失败问题", null, null,
                ConversationTurnStatus.PENDING, null, NOW, null);
        WorkspaceAccessService access = new WorkspaceAccessService(
                new TestWorkspaceRepository(resolved), Path.of("D:/agent4j"),
                Clock.fixed(NOW, ZoneOffset.UTC));
        ConversationService service = new ConversationService(
                repository, access, (id, userId, maxTurns, maxCharacters) ->
                        new ConversationContext(List.of(), 0, false),
                () -> resolved,
                (graphId, state, beforeDispatch) -> {
                    throw new IllegalStateException("启动失败");
                },
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.submitTurn(CONVERSATION_ID, "失败问题", ""))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.failedError).contains("启动失败");
    }

    @Test
    void viewerCannotArchiveConversation() {
        Actor viewer = new Actor("viewer-user", "Viewer");
        FakeConversationRepository repository = new FakeConversationRepository();
        repository.conversation = new ConversationRecord(
                CONVERSATION_ID, WORKSPACE_ID, "owner", "标题",
                ConversationStatus.ACTIVE, NOW, NOW);
        WorkspaceAccessService access = new WorkspaceAccessService(
                new TestWorkspaceRepository(viewer, WorkspacePermission.VIEWER),
                Path.of("D:/agent4j"), Clock.fixed(NOW, ZoneOffset.UTC));
        ConversationService service = new ConversationService(
                repository, access,
                (id, userId, maxTurns, maxCharacters) ->
                        new ConversationContext(List.of(), 0, false),
                () -> viewer,
                new CapturingStarter(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.archive(CONVERSATION_ID))
                .isInstanceOf(WorkspaceAccessService.WorkspaceAccessDeniedException.class);
    }

    @Test
    void operatorCanDeleteConversationWithoutDeletingWorkspace() {
        Actor operator = new Actor("delete-user", "Delete");
        FakeConversationRepository repository = new FakeConversationRepository();
        repository.conversation = new ConversationRecord(
                CONVERSATION_ID, WORKSPACE_ID, operator.userId(), "标题",
                ConversationStatus.ACTIVE, NOW, NOW);
        ConversationService service = new ConversationService(
                repository,
                new WorkspaceAccessService(
                        new TestWorkspaceRepository(operator), Path.of("D:/agent4j"),
                        Clock.fixed(NOW, ZoneOffset.UTC)),
                (id, userId, maxTurns, maxCharacters) -> new ConversationContext(List.of(), 0, false),
                () -> operator,
                new CapturingStarter(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        service.delete(CONVERSATION_ID);

        assertThat(repository.deletedConversationId).isEqualTo(CONVERSATION_ID);
        assertThat(repository.deletedUserId).isEqualTo(operator.userId());
    }

    @Test
    void archivedConversationIsHiddenByDefaultAndVisibleWhenRequested() {
        Actor operator = new Actor("list-user", "List");
        FakeConversationRepository repository = new FakeConversationRepository();
        repository.conversation = new ConversationRecord(
                CONVERSATION_ID, WORKSPACE_ID, operator.userId(), "标题",
                ConversationStatus.ARCHIVED, NOW, NOW);
        ConversationService service = new ConversationService(
                repository,
                new WorkspaceAccessService(
                        new TestWorkspaceRepository(operator), Path.of("D:/agent4j"),
                        Clock.fixed(NOW, ZoneOffset.UTC)),
                (id, userId, maxTurns, maxCharacters) -> new ConversationContext(List.of(), 0, false),
                () -> operator,
                new CapturingStarter(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.listConversations(WORKSPACE_ID, "", false)).isEmpty();
        assertThat(service.listConversations(WORKSPACE_ID, "", true))
                .containsExactly(repository.conversation);
        assertThat(repository.lastIncludeArchived).isTrue();
    }

    @Test
    void rejectsNonHttpReviewerUrlBeforeCreatingRun() {
        Actor operator = new Actor("operator-user", "Operator");
        FakeConversationRepository repository = new FakeConversationRepository();
        repository.conversation = new ConversationRecord(
                CONVERSATION_ID, WORKSPACE_ID, operator.userId(), "标题",
                ConversationStatus.ACTIVE, NOW, NOW);
        repository.pending = new ConversationTurnRecord(
                UUID.randomUUID(), CONVERSATION_ID, 1, "问题", null, null,
                ConversationTurnStatus.PENDING, null, NOW, null);
        CapturingStarter starter = new CapturingStarter();
        ConversationService service = new ConversationService(
                repository,
                new WorkspaceAccessService(
                        new TestWorkspaceRepository(operator), Path.of("D:/agent4j"),
                        Clock.fixed(NOW, ZoneOffset.UTC)),
                (id, userId, maxTurns, maxCharacters) ->
                        new ConversationContext(List.of(), 0, false),
                () -> operator,
                starter,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.submitTurn(
                CONVERSATION_ID, "问题", "file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reviewerUrl");
        assertThat(starter.state).isNull();
    }

    @Test
    void listTurnsReconcilesTerminalRunBeforeReturningConversationHistory() {
        Actor resolved = new Actor("reconcile-user", "Reconcile");
        FakeConversationRepository repository = new FakeConversationRepository();
        repository.conversation = new ConversationRecord(
                CONVERSATION_ID, WORKSPACE_ID, resolved.userId(), "标题",
                ConversationStatus.ACTIVE, NOW, NOW);
        UUID runId = UUID.randomUUID();
        repository.turns = List.of(new ConversationTurnRecord(
                UUID.randomUUID(), CONVERSATION_ID, 1, "问题", null, runId,
                ConversationTurnStatus.RUNNING, null, NOW, null));
        RunCheckpoint completed = new RunCheckpoint(
                runId, 3, "code-agent", RunStatus.COMPLETED,
                AgentState.empty().withVariable("final_response", "补偿回答"),
                null, null, null, null, null, NOW);
        ConversationRunProjector projector = new ConversationRunProjector(
                repository, new FixedCheckpointer(completed), Clock.fixed(NOW, ZoneOffset.UTC));
        ConversationService service = new ConversationService(
                repository, new WorkspaceAccessService(
                        new TestWorkspaceRepository(resolved), Path.of("D:/agent4j"),
                        Clock.fixed(NOW, ZoneOffset.UTC)),
                (id, userId, maxTurns, maxCharacters) ->
                        new ConversationContext(List.of(), 0, false),
                () -> resolved, new CapturingStarter(), projector,
                Clock.fixed(NOW, ZoneOffset.UTC));

        List<ConversationTurnRecord> turns = service.listTurns(CONVERSATION_ID);

        assertThat(turns).singleElement()
                .extracting(ConversationTurnRecord::assistantContent)
                .isEqualTo("补偿回答");
        assertThat(repository.findTurnsCalls).isEqualTo(2);
    }

    @Test
    void submissionReconcilesPreviousTerminalRunBeforeLoadingConversationContext() {
        Actor resolved = new Actor("continue-user", "Continue");
        FakeConversationRepository repository = new FakeConversationRepository();
        repository.conversation = new ConversationRecord(
                CONVERSATION_ID, WORKSPACE_ID, resolved.userId(), "标题",
                ConversationStatus.ACTIVE, NOW, NOW);
        UUID previousRunId = UUID.randomUUID();
        repository.turns = List.of(new ConversationTurnRecord(
                UUID.randomUUID(), CONVERSATION_ID, 1, "上一问", null, previousRunId,
                ConversationTurnStatus.RUNNING, null, NOW, null));
        repository.pending = new ConversationTurnRecord(
                UUID.randomUUID(), CONVERSATION_ID, 2, "下一问", null, null,
                ConversationTurnStatus.PENDING, null, NOW, null);
        RunCheckpoint completed = new RunCheckpoint(
                previousRunId, 3, "code-agent", RunStatus.COMPLETED,
                AgentState.empty().withVariable("final_response", "上一答"),
                null, null, null, null, null, NOW);
        ConversationRunProjector projector = new ConversationRunProjector(
                repository, new FixedCheckpointer(completed), Clock.fixed(NOW, ZoneOffset.UTC));
        ConversationContextProvider contextProvider = (id, userId, maxTurns, maxCharacters) -> {
            assertThat(repository.turns).singleElement()
                    .extracting(ConversationTurnRecord::status)
                    .isEqualTo(ConversationTurnStatus.COMPLETED);
            return new ConversationContext(
                    List.of(ChatMessage.user("上一问"), ChatMessage.assistant("上一答")),
                    1, false);
        };
        ConversationService service = new ConversationService(
                repository, new WorkspaceAccessService(
                        new TestWorkspaceRepository(resolved), Path.of("D:/agent4j"),
                        Clock.fixed(NOW, ZoneOffset.UTC)),
                contextProvider, () -> resolved, new CapturingStarter(), projector,
                Clock.fixed(NOW, ZoneOffset.UTC));

        ConversationTurnRecord submitted = service.submitTurn(
                CONVERSATION_ID, "下一问", "");

        assertThat(submitted.status()).isEqualTo(ConversationTurnStatus.RUNNING);
    }

    private static final class CapturingStarter implements ConversationRunStarter {
        private AgentState state;

        @Override
        public RunCheckpoint start(
                String graphId,
                AgentState initialState,
                Consumer<RunCheckpoint> beforeDispatch) {
            state = initialState;
            RunCheckpoint checkpoint = new RunCheckpoint(
                    UUID.randomUUID(), 0, graphId, RunStatus.RUNNING, initialState,
                    "planner", null, null, null, null, NOW);
            beforeDispatch.accept(checkpoint);
            return checkpoint;
        }
    }

    private static final class FakeConversationRepository implements ConversationRepository {
        private ConversationRecord conversation;
        private ConversationTurnRecord pending;
        private ConversationTurnRecord running;
        private List<ConversationTurnRecord> turns = List.of();
        private int findTurnsCalls;
        private UUID deletedConversationId;
        private String deletedUserId;
        private boolean lastIncludeArchived;

        @Override
        public Optional<ConversationRecord> findConversation(UUID conversationId, String userId) {
            return Optional.ofNullable(conversation);
        }

        @Override
        public List<ConversationRecord> findConversations(
                UUID workspaceId, String userId, String query, boolean includeArchived) {
            lastIncludeArchived = includeArchived;
            if (!includeArchived && conversation != null
                    && conversation.status() == ConversationStatus.ARCHIVED) {
                return List.of();
            }
            return conversation == null ? List.of() : List.of(conversation);
        }

        @Override
        public void deleteConversation(UUID conversationId, String userId, Instant now) {
            deletedConversationId = conversationId;
            deletedUserId = userId;
            conversation = null;
        }

        @Override
        public ConversationTurnRecord createPendingTurn(UUID conversationId, String userId,
                                                         String userContent, Instant now) {
            return pending;
        }

        @Override
        public ConversationRecord renameConversation(UUID conversationId, String userId,
                                                      String title, Instant now) {
            conversation = new ConversationRecord(
                    conversation.conversationId(), conversation.workspaceId(), conversation.createdBy(),
                    title, conversation.status(), conversation.createdAt(), now);
            return conversation;
        }

        @Override
        public Optional<ConversationTurnRecord> findTurnByRunId(UUID runId) {
            return turns.stream()
                    .filter(turn -> runId.equals(turn.runId()))
                    .findFirst();
        }

        @Override
        public ConversationTurnRecord markTurnRunning(UUID turnId, UUID runId, Instant now) {
            running = new ConversationTurnRecord(
                    pending.turnId(), pending.conversationId(), pending.turnIndex(),
                    pending.userContent(), null, runId, ConversationTurnStatus.RUNNING,
                    null, pending.createdAt(), null);
            return running;
        }

        private String failedError;

        @Override
        public ConversationTurnRecord markTurnFailed(UUID turnId, String error, Instant now) {
            failedError = error;
            return new ConversationTurnRecord(
                    pending.turnId(), pending.conversationId(), pending.turnIndex(),
                    pending.userContent(), null, null, ConversationTurnStatus.FAILED,
                    error, pending.createdAt(), now);
        }

        @Override
        public ConversationTurnRecord markTurnCompleted(UUID turnId, String assistantContent, Instant now) {
            ConversationTurnRecord current = turns.stream()
                    .filter(turn -> turn.turnId().equals(turnId))
                    .findFirst()
                    .orElseThrow();
            ConversationTurnRecord completed = new ConversationTurnRecord(
                    current.turnId(), current.conversationId(), current.turnIndex(),
                    current.userContent(), assistantContent, current.runId(),
                    ConversationTurnStatus.COMPLETED, null, current.createdAt(), now);
            turns = List.of(completed);
            return completed;
        }

        @Override
        public List<ConversationTurnRecord> findTurns(UUID conversationId, String userId) {
            findTurnsCalls++;
            return turns;
        }
    }

    private static final class FixedCheckpointer implements Checkpointer {
        private final RunCheckpoint checkpoint;

        private FixedCheckpointer(RunCheckpoint checkpoint) {
            this.checkpoint = checkpoint;
        }

        @Override
        public RunCheckpoint create(UUID runId, String graphId, AgentState initialState, String entryNode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RunCheckpoint append(CheckpointAppend append) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RunCheckpoint> loadLatest(UUID runId) {
            return runId.equals(checkpoint.runId()) ? Optional.of(checkpoint) : Optional.empty();
        }

        @Override
        public List<RunCheckpoint> loadHistory(UUID runId) {
            return List.of(checkpoint);
        }

        @Override
        public List<RunCheckpoint> loadLatestByStatus(RunStatus status) {
            return checkpoint.status() == status ? List.of(checkpoint) : List.of();
        }
    }

    private static final class TestWorkspaceRepository implements WorkspaceRepository {
        private final Actor actor;
        private final WorkspacePermission permission;

        private TestWorkspaceRepository(Actor actor) {
            this(actor, WorkspacePermission.OPERATOR);
        }

        private TestWorkspaceRepository(Actor actor, WorkspacePermission permission) {
            this.actor = actor;
            this.permission = permission;
        }

        @Override
        public Optional<WorkspaceRecord> findWorkspace(UUID workspaceId, String userId) {
            return workspaceId.equals(WORKSPACE_ID) && userId.equals(actor.userId())
                    ? Optional.of(new WorkspaceRecord(
                            WORKSPACE_ID, actor.userId(), "工作区", Path.of("D:/agent4j"),
                            "repo-exact", permission, NOW, NOW))
                    : Optional.empty();
        }

        @Override
        public List<WorkspaceRecord> findWorkspaces(String userId) {
            return List.of();
        }

        @Override
        public WorkspaceRecord createWorkspace(UUID workspaceId, Actor owner, String displayName,
                                                Path workspacePath, String repositoryId, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorkspaceRecord ensureDefaultWorkspace(UUID workspaceId, Actor owner, String displayName,
                                                       Path workspacePath, String repositoryId, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void ensureUser(Actor actor, Instant now) {
        }

        @Override
        public void grantMember(UUID workspaceId, String userId, WorkspacePermission permission, Instant now) {
        }
    }
}
