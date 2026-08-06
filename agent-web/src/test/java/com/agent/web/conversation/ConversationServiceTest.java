package com.agent.web.conversation;

import com.agent.core.conversation.ConversationContext;
import com.agent.core.conversation.ConversationContextProvider;
import com.agent.core.engine.AgentState;
import com.agent.core.engine.RunCheckpoint;
import com.agent.core.engine.RunStatus;
import com.agent.core.llm.ChatMessage;
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
import java.util.Optional;
import java.util.UUID;

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

        ConversationTurnRecord result = service.submitTurn(CONVERSATION_ID, "当前问题", "");

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
                .containsEntry("conversation.turnId", repository.pending.turnId().toString());
        assertThat(starter.state.variables()).doesNotContainKey("request.userId");
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
                (graphId, state) -> {
                    throw new IllegalStateException("启动失败");
                },
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.submitTurn(CONVERSATION_ID, "失败问题", ""))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.failedError).contains("启动失败");
    }

    private static final class CapturingStarter implements ConversationRunStarter {
        private AgentState state;

        @Override
        public RunCheckpoint start(String graphId, AgentState initialState) {
            state = initialState;
            return new RunCheckpoint(
                    UUID.randomUUID(), 0, graphId, RunStatus.RUNNING, initialState,
                    "planner", null, null, null, null, NOW);
        }
    }

    private static final class FakeConversationRepository implements ConversationRepository {
        private ConversationRecord conversation;
        private ConversationTurnRecord pending;
        private ConversationTurnRecord running;

        @Override
        public Optional<ConversationRecord> findConversation(UUID conversationId, String userId) {
            return Optional.ofNullable(conversation);
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
        public List<ConversationTurnRecord> findTurns(UUID conversationId, String userId) {
            return List.of();
        }
    }

    private static final class TestWorkspaceRepository implements WorkspaceRepository {
        private final Actor actor;

        private TestWorkspaceRepository(Actor actor) {
            this.actor = actor;
        }

        @Override
        public Optional<WorkspaceRecord> findWorkspace(UUID workspaceId, String userId) {
            return workspaceId.equals(WORKSPACE_ID) && userId.equals(actor.userId())
                    ? Optional.of(new WorkspaceRecord(
                            WORKSPACE_ID, actor.userId(), "工作区", Path.of("D:/agent4j"),
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
    }
}
