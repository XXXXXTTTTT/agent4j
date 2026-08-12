package com.agent.web.controller;

import com.agent.core.cli.CliCommandCatalog;
import com.agent.core.cli.CliCommandDefinition;
import com.agent.core.cli.CliRiskLevel;
import com.agent.core.cli.WorkspaceTerminalTargetResolver;
import com.agent.core.engine.AgentRunService;
import com.agent.core.engine.AgentState;
import com.agent.core.engine.RunCheckpoint;
import com.agent.core.engine.RunStatus;
import com.agent.core.intent.RequiredCapability;
import com.agent.web.identity.Actor;
import com.agent.web.identity.ActorResolver;
import com.agent.web.workspace.WorkspaceAccessService;
import com.agent.web.workspace.WorkspacePermission;
import com.agent.web.workspace.WorkspaceRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest(controllers = CliCommandController.class)
@Import(RunExceptionHandler.class)
class CliCommandControllerTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("ea6e28c7-b006-4226-8e4e-49011df4897a");
    private static final UUID RUN_ID = UUID.fromString("c94258b8-0f07-4d34-9b1a-cb3b38bdf4ef");

    @TempDir
    Path workspacePath;

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private AgentRunService runService;

    @MockBean
    private CliCommandCatalog commandCatalog;

    @MockBean
    private WorkspaceAccessService workspaceAccessService;

    @MockBean
    private ActorResolver actorResolver;

    @MockBean
    private WorkspaceTerminalTargetResolver workspaceTargetResolver;

    @Test
    void listsOnlyTheExactGovernedCommandFields() {
        when(actorResolver.current()).thenReturn(new Actor("user-1", "用户一"));
        when(workspaceAccessService.requireWorkspace(
                WORKSPACE_ID, "user-1", WorkspacePermission.OPERATOR))
                .thenReturn(workspace());
        when(commandCatalog.list()).thenReturn(List.of(new CliCommandDefinition(
                "test.maven", "mvn", List.of("test"), CliRiskLevel.READ_ONLY,
                Set.of(RequiredCapability.TERMINAL))));

        webTestClient.get()
                .uri("/api/workspaces/{workspaceId}/cli/commands", WORKSPACE_ID)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].name").isEqualTo("test.maven")
                .jsonPath("$[0].executable").isEqualTo("mvn")
                .jsonPath("$[0].fixedArguments[0]").isEqualTo("test")
                .jsonPath("$[0].riskLevel").isEqualTo("READ_ONLY")
                .jsonPath("$[0].requiredCapabilities[0]").isEqualTo("TERMINAL")
                .jsonPath("$[0].maxArguments").isEqualTo(64)
                .jsonPath("$[0].description").doesNotExist();
    }

    @Test
    void startsDedicatedGovernedCliRunWithExactStateVariables() throws Exception {
        when(actorResolver.current()).thenReturn(new Actor("user-1", "用户一"));
        when(workspaceAccessService.requireWorkspace(
                WORKSPACE_ID, "user-1", WorkspacePermission.OPERATOR))
                .thenReturn(workspace());
        Path bash = Files.createFile(workspacePath.resolve("bash.exe"));
        when(workspaceTargetResolver.resolve(workspacePath)).thenReturn(
                new com.agent.sandbox.pty.PtyTarget(bash, workspacePath));
        when(commandCatalog.find("test.maven")).thenReturn(java.util.Optional.of(
                new CliCommandDefinition("test.maven", "mvn", List.of("test"),
                        CliRiskLevel.READ_ONLY, Set.of(RequiredCapability.TERMINAL))));
        when(runService.start(eq("governed-cli"), any(AgentState.class))).thenReturn(checkpoint());

        webTestClient.post()
                .uri("/api/workspaces/{workspaceId}/cli/runs", WORKSPACE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"commandName":"test.maven","arguments":["-q"],"timeoutSeconds":30}
                        """)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.graphId").isEqualTo("governed-cli");

        ArgumentCaptor<AgentState> state = ArgumentCaptor.forClass(AgentState.class);
        verify(runService).start(eq("governed-cli"), state.capture());
        verify(commandCatalog).authorize(any(com.agent.core.cli.CliCommandIntent.class),
                any(com.agent.core.cli.CliAuthorizationContext.class));
        assertThat(state.getValue().variables()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                "ops.commandName", "test.maven",
                "ops.commandArguments", "[\"-q\"]",
                "coder.workspacePath", workspacePath.toString(),
                "planner.requiredCapabilities", "TERMINAL"));
    }

    @Test
    void rejectsShellAndApprovalFieldsBeforeStartingRun() {
        webTestClient.post()
                .uri("/api/workspaces/{workspaceId}/cli/runs", WORKSPACE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"commandName":"test.maven","arguments":[],"timeoutSeconds":30,
                         "shell":"mvn test","approval":true,"bashCommand":"mvn test"}
                        """)
                .exchange()
                .expectStatus().isBadRequest();
    }

    private WorkspaceRecord workspace() {
        return new WorkspaceRecord(
                WORKSPACE_ID, "user-1", "测试工作区", workspacePath, "repo-1",
                WorkspacePermission.OWNER, Instant.parse("2026-08-12T00:00:00Z"),
                Instant.parse("2026-08-12T00:00:00Z"));
    }

    private RunCheckpoint checkpoint() {
        return new RunCheckpoint(
                RUN_ID, 0, "governed-cli", RunStatus.RUNNING, AgentState.empty(), "ops",
                null, null, null, null, Instant.parse("2026-08-12T00:00:00Z"));
    }
}
