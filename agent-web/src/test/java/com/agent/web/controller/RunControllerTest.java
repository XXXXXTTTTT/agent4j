package com.agent.web.controller;

import com.agent.core.engine.AgentRunService;
import com.agent.core.engine.AgentState;
import com.agent.core.engine.ApprovalCommand;
import com.agent.core.engine.ApprovalDecision;
import com.agent.core.engine.CheckpointConflictException;
import com.agent.core.engine.GraphNotFoundException;
import com.agent.core.engine.RunCheckpoint;
import com.agent.core.engine.RunNotFoundException;
import com.agent.core.engine.RunStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@WebFluxTest(controllers = RunController.class)
@Import(RunExceptionHandler.class)
class RunControllerTest {

    private static final UUID RUN_ID = UUID.fromString("c4857ed1-9ad4-448f-94a2-e60961be6d20");
    private static final Instant CREATED_AT = Instant.parse("2026-08-01T09:00:00Z");

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private AgentRunService runService;

    @Test
    void startsRunAndReturnsAcceptedView() {
        when(runService.start(eq("coder-ops-reviewer"), any(AgentState.class)))
                .thenReturn(runningCheckpoint());

        webTestClient.post()
                .uri("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "graphId": "coder-ops-reviewer",
                          "initialState": {
                            "messages": [],
                            "variables": {},
                            "trace": []
                          }
                        }
                        """)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.runId").isEqualTo(RUN_ID.toString())
                .jsonPath("$.version").isEqualTo(0)
                .jsonPath("$.graphId").isEqualTo("coder-ops-reviewer")
                .jsonPath("$.status").isEqualTo("RUNNING")
                .jsonPath("$.state.messages").isArray()
                .jsonPath("$.state.variables").isMap()
                .jsonPath("$.state.trace").isArray()
                .jsonPath("$.nextNode").isEqualTo("coder")
                .jsonPath("$.interruptRequest").doesNotExist()
                .jsonPath("$.approvalDecision").doesNotExist()
                .jsonPath("$.approvalReason").doesNotExist()
                .jsonPath("$.error").doesNotExist()
                .jsonPath("$.createdAt").isEqualTo("2026-08-01T09:00:00Z");
    }

    @Test
    void getsRunAndApprovesWaitingRun() {
        RunCheckpoint running = new RunCheckpoint(
                RUN_ID,
                2,
                "coder-ops-reviewer",
                RunStatus.RUNNING,
                AgentState.empty(),
                "ops",
                null,
                ApprovalDecision.APPROVE,
                "已核对命令和工作区",
                null,
                CREATED_AT);
        when(runService.get(RUN_ID)).thenReturn(runningCheckpoint());
        when(runService.decide(
                RUN_ID,
                new ApprovalCommand(
                        ApprovalDecision.APPROVE,
                        1,
                        "已核对命令和工作区"))).thenReturn(running);

        webTestClient.get()
                .uri("/api/runs/{runId}", RUN_ID)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.runId").isEqualTo(RUN_ID.toString())
                .jsonPath("$.status").isEqualTo("RUNNING");

        webTestClient.post()
                .uri("/api/runs/{runId}/approval", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "decision": "APPROVE",
                          "expectedVersion": 1,
                          "reason": "已核对命令和工作区"
                        }
                        """)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.version").isEqualTo(2)
                .jsonPath("$.approvalDecision").isEqualTo("APPROVE")
                .jsonPath("$.approvalReason").isEqualTo("已核对命令和工作区")
                .jsonPath("$.nextNode").isEqualTo("ops");
    }

    @Test
    void mapsUnknownGraphAndRunToNotFound() {
        when(runService.start(eq("missing"), any(AgentState.class)))
                .thenThrow(new GraphNotFoundException("missing"));
        UUID missingRun = UUID.fromString("48bfc730-ff14-4666-a164-65141c5a796b");
        when(runService.get(missingRun)).thenThrow(new RunNotFoundException(missingRun));

        webTestClient.post()
                .uri("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validStartBody("missing"))
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.title").isEqualTo("Not Found")
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.detail").isEqualTo("图未注册: missing")
                .jsonPath("$.instance").isEqualTo("/api/runs");

        webTestClient.get()
                .uri("/api/runs/{runId}", missingRun)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.instance").isEqualTo("/api/runs/" + missingRun);
    }

    @Test
    void mapsApprovalConflictToConflict() {
        when(runService.decide(eq(RUN_ID), any(ApprovalCommand.class)))
                .thenThrow(new CheckpointConflictException(RUN_ID, 1));

        webTestClient.post()
                .uri("/api/runs/{runId}/approval", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "decision": "REJECT",
                          "expectedVersion": 1,
                          "reason": "拒绝执行"
                        }
                        """)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Conflict")
                .jsonPath("$.status").isEqualTo(409)
                .jsonPath("$.detail").value(value ->
                        org.assertj.core.api.Assertions.assertThat((String) value)
                                .contains(RUN_ID.toString())
                                .contains("expectedVersion=1"));
    }

    @Test
    void rejectsMalformedAndNonExactRequests() {
        assertBadRequest("""
                {
                  "graphId": "coder-ops-reviewer",
                  "initialState": {"messages": [], "variables": {}, "trace": []},
                  "extra": true
                }
                """, "/api/runs");
        assertBadRequest("""
                {
                  "graphId": " ",
                  "initialState": {"messages": [], "variables": {}, "trace": []}
                }
                """, "/api/runs");
        assertBadRequest("""
                {
                  "decision": "approve",
                  "expectedVersion": 1,
                  "reason": "已核对"
                }
                """, "/api/runs/" + RUN_ID + "/approval");
        assertBadRequest("""
                {
                  "decision": "APPROVE",
                  "expectedVersion": -1,
                  "reason": " "
                }
                """, "/api/runs/" + RUN_ID + "/approval");

        webTestClient.get()
                .uri("/api/runs/not-a-uuid")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400);
    }

    @Test
    void mapsUnhandledInfrastructureFailureToInternalServerError() {
        when(runService.get(RUN_ID)).thenThrow(new IllegalStateException("database unavailable"));

        webTestClient.get()
                .uri("/api/runs/{runId}", RUN_ID)
                .exchange()
                .expectStatus().is5xxServerError()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Internal Server Error")
                .jsonPath("$.status").isEqualTo(500)
                .jsonPath("$.detail").isEqualTo("内部服务器错误")
                .jsonPath("$.instance").isEqualTo("/api/runs/" + RUN_ID);
    }

    private void assertBadRequest(String body, String path) {
        webTestClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.title").isEqualTo("Bad Request")
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.detail").isNotEmpty()
                .jsonPath("$.instance").isEqualTo(path);
    }

    private String validStartBody(String graphId) {
        return """
                {
                  "graphId": "%s",
                  "initialState": {"messages": [], "variables": {}, "trace": []}
                }
                """.formatted(graphId);
    }

    private RunCheckpoint runningCheckpoint() {
        return new RunCheckpoint(
                RUN_ID,
                0,
                "coder-ops-reviewer",
                RunStatus.RUNNING,
                AgentState.empty(),
                "coder",
                null,
                null,
                null,
                null,
                CREATED_AT);
    }
}
