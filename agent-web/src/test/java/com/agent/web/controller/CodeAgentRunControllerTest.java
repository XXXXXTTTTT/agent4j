package com.agent.web.controller;

import com.agent.core.engine.AgentRunService;
import com.agent.core.engine.AgentState;
import com.agent.core.engine.RunCheckpoint;
import com.agent.core.engine.RunStatus;
import com.agent.web.config.ProductionAgentProperties;
import com.agent.web.identity.Actor;
import com.agent.web.identity.ActorResolver;
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

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private AgentRunService runService;

    @MockBean
    private ProductionAgentProperties properties;

    @MockBean
    private ActorResolver actorResolver;

    @Test
    void startsCodeAgentWithExactTaskStateKeys() throws Exception {
        Path workspace = Path.of(".").toAbsolutePath().normalize();
        when(properties.enabled()).thenReturn(true);
        when(properties.workspace()).thenReturn(workspace);
        when(properties.repositoryId()).thenReturn("configured-repo");
        when(properties.userId()).thenReturn("configured-user");
        when(actorResolver.current()).thenReturn(new Actor("resolved-user", "Resolved"));
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
                .containsEntry("planner.repositoryId", "request-repo")
                .containsEntry("planner.userId", "resolved-user")
                .containsEntry("coder.workspacePath", workspace.toRealPath().toString())
                .containsEntry("reviewer.url", "https://application.test");
    }
}
