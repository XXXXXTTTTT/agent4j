package com.agent.web.controller;

import com.agent.web.conversation.ConversationRecord;
import com.agent.web.conversation.ConversationService;
import com.agent.web.conversation.ConversationStatus;
import com.agent.web.conversation.ConversationTurnRecord;
import com.agent.web.conversation.ConversationTurnStatus;
import com.agent.web.identity.Actor;
import com.agent.web.identity.ActorResolver;
import com.agent.web.workspace.WorkspaceAccessService;
import com.agent.web.workspace.WorkspacePermission;
import com.agent.web.workspace.WorkspaceRecord;
import com.agent.core.orchestration.AgentRole;
import com.agent.core.orchestration.OrchestrationMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@WebFluxTest(
        controllers = {ConversationController.class, WorkspaceController.class, IdentityController.class},
        properties = "agent.production.enabled=true")
@Import(RunExceptionHandler.class)
class ConversationControllerTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("8a2c6af6-09c8-43b7-b2d0-07f2612ae0e1");
    private static final UUID CONVERSATION_ID = UUID.fromString("7a2f60a2-bd64-4f3e-87f1-1d11bdaf1aa2");
    private static final UUID TURN_ID = UUID.fromString("b08d47f0-2a14-4755-9f96-4c7c4d5e6a6b");
    private static final Instant NOW = Instant.parse("2026-08-07T00:00:00Z");

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private ConversationService conversationService;

    @MockBean
    private WorkspaceAccessService workspaceAccessService;

    @MockBean
    private ActorResolver actorResolver;

    @Test
    void returnsConversationAndTurnsWithExactFields() {
        ConversationRecord conversation = new ConversationRecord(
                CONVERSATION_ID, WORKSPACE_ID, "local", "首轮问题",
                ConversationStatus.ACTIVE, NOW, NOW);
        ConversationTurnRecord turn = new ConversationTurnRecord(
                TURN_ID, CONVERSATION_ID, 1, "首轮问题", "回答", UUID.randomUUID(),
                ConversationTurnStatus.COMPLETED, null, NOW, NOW);
        when(conversationService.getConversation(CONVERSATION_ID)).thenReturn(conversation);
        when(conversationService.listTurns(CONVERSATION_ID)).thenReturn(List.of(turn));

        webTestClient.get().uri("/api/conversations/{id}", CONVERSATION_ID)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.conversationId").isEqualTo(CONVERSATION_ID.toString())
                .jsonPath("$.workspaceId").isEqualTo(WORKSPACE_ID.toString())
                .jsonPath("$.title").isEqualTo("首轮问题")
                .jsonPath("$.status").isEqualTo("ACTIVE")
                .jsonPath("$.createdBy").isEqualTo("local");

        webTestClient.get().uri("/api/conversations/{id}/turns", CONVERSATION_ID)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].turnId").isEqualTo(TURN_ID.toString())
                .jsonPath("$[0].assistantContent").isEqualTo("回答")
                .jsonPath("$[0].status").isEqualTo("COMPLETED");
    }

    @Test
    void submitsTurnAndRejectsUnknownFields() {
        ConversationTurnRecord turn = new ConversationTurnRecord(
                TURN_ID, CONVERSATION_ID, 2, "继续", null, UUID.randomUUID(),
                ConversationTurnStatus.RUNNING, null, NOW, null);
        when(conversationService.submitTurn(CONVERSATION_ID, "继续", "https://example.test"))
                .thenReturn(turn);

        webTestClient.post().uri("/api/conversations/{id}/turns", CONVERSATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"content":"继续","reviewerUrl":"https://example.test"}
                        """)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.runId").isNotEmpty()
                .jsonPath("$.turnId").isEqualTo(TURN_ID.toString());

        webTestClient.post().uri("/api/conversations/{id}/turns", CONVERSATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{" + "\"content\":\"继续\",\"userId\":\"other\"}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void submitsOrchestrationSettingsWithExactContract() {
        ConversationTurnRecord turn = new ConversationTurnRecord(
                TURN_ID, CONVERSATION_ID, 2, "并行研究", null, UUID.randomUUID(),
                ConversationTurnStatus.RUNNING, null, NOW, null);
        when(conversationService.submitTurn(
                CONVERSATION_ID,
                "并行研究",
                "",
                "primary-group",
                OrchestrationMode.PARALLEL_RESEARCH,
                Map.of(AgentRole.RESEARCHER, "research-group")))
                .thenReturn(turn);

        webTestClient.post().uri("/api/conversations/{id}/turns", CONVERSATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"content":"并行研究","reviewerUrl":"","modelGroupId":"primary-group",
                         "orchestrationMode":"PARALLEL_RESEARCH",
                         "roleModelGroups":{"RESEARCHER":"research-group"}}
                        """)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.turnId").isEqualTo(TURN_ID.toString());

        verify(conversationService).submitTurn(
                CONVERSATION_ID,
                "并行研究",
                "",
                "primary-group",
                OrchestrationMode.PARALLEL_RESEARCH,
                Map.of(AgentRole.RESEARCHER, "research-group"));
    }

    @Test
    void rejectsUnknownOrchestrationModeAsBadRequest() {
        webTestClient.post().uri("/api/conversations/{id}/turns", CONVERSATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"content":"继续","modelGroupId":"primary-group",
                         "orchestrationMode":"UNKNOWN"}
                        """)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void mapsInvalidOrchestrationContractToBadRequest() {
        when(conversationService.submitTurn(
                eq(CONVERSATION_ID), eq("继续"), eq(""), eq("primary-group"),
                eq(OrchestrationMode.REVIEW_LOOP), any()))
                .thenThrow(new IllegalArgumentException("VERIFIER模型组不能为空"));

        webTestClient.post().uri("/api/conversations/{id}/turns", CONVERSATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"content":"继续","reviewerUrl":"","modelGroupId":"primary-group",
                         "orchestrationMode":"REVIEW_LOOP",
                         "roleModelGroups":{"VERIFIER":" "}}
                        """)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void rejectsRoleOverridesWithoutOrchestrationMode() {
        when(conversationService.submitTurn(
                eq(CONVERSATION_ID), eq("继续"), eq(""), eq("primary-group"),
                isNull(), any()))
                .thenThrow(new IllegalArgumentException("编排模式不能为空"));

        webTestClient.post().uri("/api/conversations/{id}/turns", CONVERSATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"content":"继续","reviewerUrl":"","modelGroupId":"primary-group",
                         "roleModelGroups":{"VERIFIER":"verify-group"}}
                        """)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void listsWorkspacesAndMapsAccessErrors() {
        Actor actor = new Actor("local", "本地用户");
        when(actorResolver.current()).thenReturn(actor);
        WorkspaceRecord workspace = new WorkspaceRecord(
                WORKSPACE_ID, "local", "工作区", Path.of("D:/agent4j"), "repo",
                WorkspacePermission.OWNER, NOW, NOW);
        when(workspaceAccessService.list(actor)).thenReturn(List.of(workspace));

        webTestClient.get().uri("/api/workspaces")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].workspaceId").isEqualTo(WORKSPACE_ID.toString())
                .jsonPath("$[0].permission").isEqualTo("OWNER");
    }

    @Test
    void deletesConversationAndSupportsArchivedFilter() {
        ConversationRecord archived = new ConversationRecord(
                CONVERSATION_ID, WORKSPACE_ID, "local", "归档",
                ConversationStatus.ARCHIVED, NOW, NOW);
        when(conversationService.listConversations(WORKSPACE_ID, "", true))
                .thenReturn(List.of(archived));
        when(conversationService.delete(CONVERSATION_ID)).thenReturn(archived);

        webTestClient.get().uri(uriBuilder -> uriBuilder
                        .path("/api/workspaces/{id}/conversations")
                        .queryParam("includeArchived", "true")
                        .build(WORKSPACE_ID))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].status").isEqualTo("ARCHIVED");

        webTestClient.delete().uri("/api/conversations/{id}", CONVERSATION_ID)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.conversationId").isEqualTo(CONVERSATION_ID.toString());
    }

    @Test
    void mapsConversationNotFoundConflictAndWorkspaceForbidden() {
        when(conversationService.getConversation(CONVERSATION_ID))
                .thenThrow(new ConversationService.ConversationNotFoundException(CONVERSATION_ID));
        when(conversationService.submitTurn(eq(CONVERSATION_ID), any(), any()))
                .thenThrow(new com.agent.web.persistence.JdbcConversationRepository.ConversationConflictException(
                        "会话已有活动轮次"));
        when(workspaceAccessService.list(any()))
                .thenThrow(new WorkspaceAccessService.WorkspaceAccessDeniedException(
                        WORKSPACE_ID, WorkspacePermission.OPERATOR));

        webTestClient.get().uri("/api/conversations/{id}", CONVERSATION_ID)
                .exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.status").isEqualTo(404);
        webTestClient.post().uri("/api/conversations/{id}/turns", CONVERSATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"content\":\"继续\"}")
                .exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.status").isEqualTo(409);
        webTestClient.get().uri("/api/workspaces")
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.status").isEqualTo(403);
    }
}
