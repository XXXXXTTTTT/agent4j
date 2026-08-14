package com.agent.web.controller;

import com.agent.core.command.CommandChannel;
import com.agent.core.command.CommandDefinition;
import com.agent.core.command.CommandDispatcher;
import com.agent.core.command.CommandPermission;
import com.agent.core.command.CommandRegistry;
import com.agent.core.command.CommandResult;
import com.agent.core.command.CommandSource;
import com.agent.web.command.WorkspaceCommandRuntimeProvider;
import com.agent.web.identity.Actor;
import com.agent.web.identity.ActorResolver;
import com.agent.web.workspace.WorkspaceAccessService;
import com.agent.web.workspace.WorkspacePermission;
import com.agent.web.workspace.WorkspaceRecord;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest(controllers = CommandController.class,
        properties = "agent.production.enabled=true")
@Import(RunExceptionHandler.class)
class CommandControllerTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("ea6e28c7-b006-4226-8e4e-49011df4897a");
    private static final UUID CONVERSATION_ID = UUID.fromString("c94258b8-0f07-4d34-9b1a-cb3b38bdf4ef");

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private WorkspaceCommandRuntimeProvider runtimeProvider;

    @MockBean
    private WorkspaceAccessService workspaceAccessService;

    @MockBean
    private ActorResolver actorResolver;

    @MockBean
    private CommandRegistry registry;

    @MockBean
    private CommandDispatcher dispatcher;

    @Test
    void listsLiveRegistrySnapshotForWorkspace() {
        when(actorResolver.current()).thenReturn(new Actor("user-1", "用户一"));
        when(workspaceAccessService.requireWorkspace(WORKSPACE_ID, "user-1", WorkspacePermission.VIEWER))
                .thenReturn(workspace());
        when(runtimeProvider.resolve(any(WorkspaceRecord.class)))
                .thenReturn(new WorkspaceCommandRuntimeProvider.Runtime(registry, dispatcher));
        when(registry.revision()).thenReturn(3L);
        when(registry.list()).thenReturn(List.of(new CommandDefinition(
                "plan", "计划", "制定计划", List.of("roadmap"), List.of(),
                CommandChannel.WORKFLOW_SKILL, CommandSource.GLOBAL, CommandPermission.OPERATOR,
                (invocation, context) -> CommandResult.forwarded("ok"))));

        webTestClient.get()
                .uri("/api/workspaces/{workspaceId}/commands", WORKSPACE_ID)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.revision").isEqualTo(3)
                .jsonPath("$.commands[0].name").isEqualTo("plan")
                .jsonPath("$.commands[0].channel").isEqualTo("WORKFLOW_SKILL")
                .jsonPath("$.commands[0].source").isEqualTo("GLOBAL")
                .jsonPath("$.commands[0].aliases[0]").isEqualTo("roadmap");
    }

    @Test
    void dispatchesExactInputWithinAuthorizedWorkspace() {
        when(actorResolver.current()).thenReturn(new Actor("user-1", "用户一"));
        when(workspaceAccessService.requireWorkspace(WORKSPACE_ID, "user-1", WorkspacePermission.OPERATOR))
                .thenReturn(workspace());
        when(runtimeProvider.resolve(any(WorkspaceRecord.class)))
                .thenReturn(new WorkspaceCommandRuntimeProvider.Runtime(registry, dispatcher));
        when(dispatcher.dispatch(eq("/plan fix login"), any()))
                .thenReturn(CommandResult.forwarded("已提交"));

        webTestClient.post()
                .uri("/api/workspaces/{workspaceId}/commands", WORKSPACE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"input\":\"/plan fix login\",\"conversationId\":\""
                        + CONVERSATION_ID + "\"}")
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.status").isEqualTo("FORWARDED");

        verify(dispatcher).dispatch(eq("/plan fix login"), any());
    }

    private WorkspaceRecord workspace() {
        return new WorkspaceRecord(
                WORKSPACE_ID, "user-1", "测试工作区", Path.of("D:/agent4j"), "repo-1",
                WorkspacePermission.OWNER, Instant.parse("2026-08-12T00:00:00Z"),
                Instant.parse("2026-08-12T00:00:00Z"));
    }
}
