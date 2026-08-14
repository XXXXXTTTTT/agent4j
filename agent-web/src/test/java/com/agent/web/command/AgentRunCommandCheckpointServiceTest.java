package com.agent.web.command;

import com.agent.core.engine.AgentRunService;
import com.agent.core.engine.AgentState;
import com.agent.core.engine.RunCheckpoint;
import com.agent.core.engine.RunStatus;
import com.agent.web.audit.ConversationAuditSink;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRunCommandCheckpointServiceTest {

    @Test
    void rewindsOnlyCheckpointFromSameConversationAndWorkspace() {
        UUID runId = UUID.fromString("c94258b8-0f07-4d34-9b1a-cb3b38bdf4ef");
        UUID workspaceId = UUID.fromString("ea6e28c7-b006-4226-8e4e-49011df4897a");
        UUID conversationId = UUID.fromString("1789dc76-2fa3-4f45-a0c1-73404f14ab6f");
        AgentState state = AgentState.empty()
                .withVariable("conversation.workspaceId", workspaceId.toString())
                .withVariable("conversation.id", conversationId.toString());
        RunCheckpoint checkpoint = new RunCheckpoint(
                runId, 0, "flow", RunStatus.RUNNING, state, "done", null, null,
                null, null, Instant.parse("2026-08-14T00:00:00Z"));
        AgentRunService runService = mock(AgentRunService.class);
        when(runService.history(runId)).thenReturn(List.of(checkpoint));
        when(runService.rewind(runId, 0)).thenReturn(checkpoint);
        AgentRunCommandCheckpointService service = new AgentRunCommandCheckpointService(
                runService, ConversationAuditSink.noop());

        var result = service.rewind(
                new com.agent.core.command.CommandContext("user-1", workspaceId.toString(), conversationId.toString()),
                runId + ":0");

        assertThat(result.status()).isEqualTo(com.agent.core.command.CommandResult.Status.COMPLETED);
    }

    @Test
    void rejectsMalformedReference() {
        AgentRunService runService = mock(AgentRunService.class);
        AgentRunCommandCheckpointService service = new AgentRunCommandCheckpointService(
                runService, ConversationAuditSink.noop());

        assertThat(service.rewind(
                new com.agent.core.command.CommandContext("user-1", "ws-1", "conv-1"),
                "bad").status()).isEqualTo(com.agent.core.command.CommandResult.Status.INVALID);
    }
}
