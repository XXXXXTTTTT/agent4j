package com.agent.web.command;

import com.agent.core.command.CommandContext;
import com.agent.core.command.CommandInvocation;
import com.agent.core.command.CommandResult;
import com.agent.web.audit.ConversationAuditSink;
import com.agent.web.conversation.ConversationService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ConversationWorkflowCommandBridgeTest {

    @Test
    void submitsRenderedTemplateToExistingConversationService() {
        ConversationService conversationService = mock(ConversationService.class);
        ConversationAuditSink auditSink = mock(ConversationAuditSink.class);
        ConversationWorkflowCommandBridge bridge = new ConversationWorkflowCommandBridge(
                conversationService, auditSink);
        UUID conversationId = UUID.fromString("c94258b8-0f07-4d34-9b1a-cb3b38bdf4ef");

        CommandResult result = bridge.submit(
                new CommandInvocation("plan", List.of("fix login"), "/plan fix login"),
                new CommandContext("user-1", "ea6e28c7-b006-4226-8e4e-49011df4897a", conversationId.toString()),
                "制定计划：fix login");

        assertThat(result.status()).isEqualTo(CommandResult.Status.FORWARDED);
        verify(conversationService).submitTurn(eq(conversationId), eq("制定计划：fix login"),
                eq(null), eq(null));
        verify(auditSink).record(org.mockito.ArgumentMatchers.any());
    }
}
