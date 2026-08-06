package com.agent.web.conversation;

import com.agent.core.engine.AgentState;
import com.agent.core.trace.TraceEvent;
import com.agent.web.persistence.JdbcConversationRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationRunProjectorTest {

    private static final Instant NOW = Instant.parse("2026-08-07T00:00:00Z");
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID TURN_ID = UUID.randomUUID();
    private static final UUID CONVERSATION_ID = UUID.randomUUID();

    @Test
    void projectsCompletedResponseUsingExactFallbackOrderAndIgnoresDuplicateEvents() {
        FakeRepository repository = new FakeRepository();
        ConversationRunProjector projector = new ConversationRunProjector(repository);
        AgentState state = AgentState.empty()
                .withVariable("final_response", "最终回答")
                .withVariable("reviewer.feedback", "审查反馈")
                .withVariable("planner.response", "规划回答");

        projector.publish(new TraceEvent.Completed(UUID.randomUUID(), RUN_ID, 3, NOW), state);
        projector.publish(new TraceEvent.Completed(UUID.randomUUID(), RUN_ID, 3, NOW), state);

        assertThat(repository.completedContents).containsExactly("最终回答");
    }

    @Test
    void projectsFailureWithFullErrorAndRejectedAsFailure() {
        FakeRepository repository = new FakeRepository();
        ConversationRunProjector projector = new ConversationRunProjector(repository);

        projector.publish(new TraceEvent.Failed(UUID.randomUUID(), RUN_ID, 2, NOW, "完整堆栈"), AgentState.empty());
        projector.publish(new TraceEvent.Rejected(
                UUID.randomUUID(), UUID.randomUUID(), 2, NOW, "ops", "用户拒绝"), AgentState.empty());

        assertThat(repository.failedErrors).containsExactly("完整堆栈", "用户拒绝");
    }

    private static final class FakeRepository implements ConversationRepository {
        private final ConversationTurnRecord turn = new ConversationTurnRecord(
                TURN_ID, CONVERSATION_ID, 1, "问题", null, RUN_ID,
                ConversationTurnStatus.RUNNING, null, NOW, null);
        private final java.util.ArrayList<String> completedContents = new java.util.ArrayList<>();
        private final java.util.ArrayList<String> failedErrors = new java.util.ArrayList<>();

        @Override
        public Optional<ConversationTurnRecord> findTurnByRunId(UUID runId, String userId) {
            return Optional.of(turn);
        }

        @Override
        public Optional<ConversationTurnRecord> findTurnByRunId(UUID runId) {
            return Optional.of(turn);
        }

        @Override
        public ConversationTurnRecord markTurnCompleted(UUID turnId, String assistantContent, Instant now) {
            completedContents.add(assistantContent);
            return turn;
        }

        @Override
        public ConversationTurnRecord markTurnFailed(UUID turnId, String error, Instant now) {
            failedErrors.add(error);
            return turn;
        }

        @Override
        public List<ConversationTurnRecord> findTurns(UUID conversationId, String userId) {
            return List.of();
        }
    }
}
