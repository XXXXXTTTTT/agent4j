package com.agent.web.controller;

import com.agent.core.engine.AgentRunService;
import com.agent.core.engine.AgentState;
import com.agent.core.engine.RunCheckpoint;
import com.agent.core.engine.RunStatus;
import com.agent.core.skill.SkillCatalogProvider;
import com.agent.core.skill.SkillCatalogSnapshot;
import com.agent.core.skill.SkillCatalogSnapshotCodec;
import com.agent.core.mcp.McpCatalogProvider;
import com.agent.core.mcp.McpCatalogSnapshot;
import com.agent.core.mcp.McpCatalogSnapshotCodec;
import com.agent.web.config.ProductionAgentProperties;
import com.agent.web.identity.Actor;
import com.agent.web.identity.ActorResolver;
import com.agent.web.workspace.WorkspaceAccessService;
import com.agent.web.workspace.WorkspacePermission;
import com.agent.web.workspace.WorkspaceRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;

@WebFluxTest(controllers = RunController.class)
@Import(RunExceptionHandler.class)
class CodeAgentRunControllerTest {

    private static final UUID RUN_ID = UUID.fromString("c4857ed1-9ad4-448f-94a2-e60961be6d20");
    private static final UUID WORKSPACE_ID = UUID.fromString("80ac9662-a954-4bcc-98e5-70e80b5f3f4b");

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private AgentRunService runService;

    @MockBean
    private ProductionAgentProperties properties;

    @MockBean
    private ActorResolver actorResolver;

    @MockBean
    private WorkspaceAccessService workspaceAccessService;

    @MockBean
    private SkillCatalogProvider skillCatalogProvider;

    @MockBean
    private McpCatalogProvider mcpCatalogProvider;

    @Test
    void startsCodeAgentWithExactTaskStateKeys() throws Exception {
        Path workspace = Path.of(".").toAbsolutePath().normalize();
        when(properties.enabled()).thenReturn(true);
        when(properties.workspace()).thenReturn(workspace);
        when(properties.repositoryId()).thenReturn("configured-repo");
        when(properties.userId()).thenReturn("configured-user");
        when(actorResolver.current()).thenReturn(new Actor("resolved-user", "Resolved"));
        when(workspaceAccessService.requireWorkspace(
                WORKSPACE_ID, "resolved-user", WorkspacePermission.OPERATOR))
                .thenReturn(new WorkspaceRecord(
                        WORKSPACE_ID, "resolved-user", "测试工作区", workspace,
                        "workspace-repository", WorkspacePermission.OPERATOR,
                        Instant.parse("2026-08-05T00:00:00Z"),
                        Instant.parse("2026-08-05T00:00:00Z")));
        when(skillCatalogProvider.resolve("resolved-user", WORKSPACE_ID)).thenReturn(
                new SkillCatalogSnapshot(1, "resolved-user", WORKSPACE_ID,
                        Instant.parse("2026-08-05T00:00:00Z"), 0, java.util.List.of(), ""));
        when(mcpCatalogProvider.resolve("resolved-user", WORKSPACE_ID)).thenReturn(
                new McpCatalogSnapshot(1, "resolved-user", WORKSPACE_ID,
                        Instant.parse("2026-08-05T00:00:00Z"), java.util.List.of(), ""));
        when(runService.start(eq("code-agent"), any(AgentState.class)))
                .thenReturn(new RunCheckpoint(
                        RUN_ID,
                        0,
                        "code-agent",
                        RunStatus.RUNNING,
                        AgentState.empty(),
                        "planner",
                        null,
                        null,
                        null,
                        null,
                        Instant.parse("2026-08-05T00:00:00Z")));

        webTestClient.post()
                .uri("/api/runs/code-agent")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "task": "修复登录超时并运行测试",
                          "workspaceId": "80ac9662-a954-4bcc-98e5-70e80b5f3f4b",
                          "repositoryId": "request-repo",
                          "reviewerUrl": "https://application.test"
                        }
                        """)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.graphId").isEqualTo("code-agent");

        ArgumentCaptor<AgentState> stateCaptor = ArgumentCaptor.forClass(AgentState.class);
        verify(runService).start(eq("code-agent"), stateCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(stateCaptor.getValue().variables())
                .containsEntry("planner.task", "修复登录超时并运行测试")
                .containsEntry("planner.repositoryId", "workspace-repository")
                .containsEntry("planner.userId", "resolved-user")
                .containsEntry("coder.workspacePath", workspace.toRealPath().toString())
                .containsEntry("conversation.workspaceId", WORKSPACE_ID.toString())
                .containsEntry("reviewer.url", "https://application.test");
        String encoded = stateCaptor.getValue().variables().get("skill.catalogSnapshot");
        org.assertj.core.api.Assertions.assertThat(new SkillCatalogSnapshotCodec(new ObjectMapper())
                .decode(encoded, "resolved-user", WORKSPACE_ID,
                        new com.agent.core.tool.DefaultToolRegistry()))
                .extracting(SkillCatalogSnapshot::workspaceId)
                .isEqualTo(WORKSPACE_ID);
        String encodedMcp = stateCaptor.getValue().variables().get("mcp.catalogSnapshot");
        org.assertj.core.api.Assertions.assertThat(new McpCatalogSnapshotCodec(new ObjectMapper())
                .decode(encodedMcp, "resolved-user", WORKSPACE_ID))
                .extracting(McpCatalogSnapshot::workspaceId)
                .isEqualTo(WORKSPACE_ID);
        verify(workspaceAccessService).requireWorkspace(
                WORKSPACE_ID, "resolved-user", WorkspacePermission.OPERATOR);
    }
}
