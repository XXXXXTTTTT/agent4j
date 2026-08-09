package com.agent.web.config;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.GraphFactory;
import com.agent.core.engine.StateGraph;
import com.agent.core.llm.LlmClient;
import com.agent.core.llm.ModelEndpoint;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.TaskType;
import com.agent.core.knowledge.KnowledgeContext;
import com.agent.core.knowledge.KnowledgeEvidence;
import com.agent.core.knowledge.KnowledgeEvidenceKind;
import com.agent.core.knowledge.KnowledgeEvidenceStatus;
import com.agent.core.nodes.CoderNode;
import com.agent.core.nodes.OpsNode;
import com.agent.core.nodes.PlannerNode;
import com.agent.core.nodes.ReviewerNode;
import com.agent.core.trace.RunLogPublisher;
import com.agent.sandbox.ast.AstService;
import com.agent.sandbox.ast.WorkspaceSnapshotService;
import com.agent.sandbox.browser.BrowserAutomation;
import com.agent.sandbox.pty.CommandResult;
import com.agent.sandbox.pty.SandboxTerminalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ProductionCodeAgentIntegrationTest {

    private static final String BASE_URL = "https://production-agent.test";
    private static final String COMPLETIONS_PATH = "/v1/chat/completions";

    @TempDir
    Path workspace;

    @Test
    void executesPlannerCoderOpsReviewerWithCompleteModelEvidence() throws Exception {
        Files.writeString(workspace.resolve("value.txt"), "before\n");
        Path bash = Files.createFile(workspace.resolve("bash.exe"));
        try (Git ignored = Git.init().setDirectory(workspace.toFile()).call()) {
            // 集成测试需要真实 Git 工作树应用 Unified Diff。
        }

        ObjectMapper objectMapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LlmClient client = new LlmClient(
                builder.build(), objectMapper, COMPLETIONS_PATH);
        ModelEndpoint codeEndpoint = endpoint("code", "code-model", client);
        ModelEndpoint visionEndpoint = endpoint("vision", "vision-model", client);
        ModelRouter router = new ModelRouter(Map.of(
                TaskType.CODE, List.of(codeEndpoint),
                TaskType.VISION, List.of(visionEndpoint),
                TaskType.QUICK_CLASSIFICATION, List.of(codeEndpoint)));
        server.expect(times(3), requestTo(BASE_URL + COMPLETIONS_PATH))
                .andRespond(request -> withSuccess(
                        responseForRequest(
                                objectMapper,
                                ((MockClientHttpRequest) request).getBodyAsString()),
                        MediaType.APPLICATION_JSON).createResponse(request));

        SandboxTerminalService terminalService = mock(SandboxTerminalService.class);
        when(terminalService.execute(any(), any())).thenReturn(
                CompletableFuture.completedFuture(
                        new CommandResult(0, "after\n", "", false)));
        BrowserAutomation browserAutomation = mock(BrowserAutomation.class);
        ProductionAgentProperties properties = new ProductionAgentProperties(
                true,
                workspace,
                "repository",
                "user",
                "",
                "PTY",
                bash.toString(),
                "python:3.12-slim",
                "/workspace",
                "",
                "",
                Duration.ofSeconds(30),
                Duration.ofSeconds(15),
                50,
                32_000,
                2,
                12,
                1_800_000,
                120_000,
                200_000,
                3,
                12_000);
        GraphFactory factory = new ProductionGraphConfiguration().codeAgentGraph(
                properties,
                router,
                request -> new com.agent.core.memory.MemoryContext("", 0),
                request -> knowledgeContext(),
                terminalService,
                browserAutomation,
                new AstService(),
                new WorkspaceSnapshotService(50, 32_000),
                RunLogPublisher.noop(),
                objectMapper);

        try (client; StateGraph graph = factory.create()) {
            AgentState result = graph.execute(AgentState.empty()
                    .withVariable(PlannerNode.TASK_KEY, "把 value.txt 改成 after 并验证")
                    .withVariable(PlannerNode.REPOSITORY_ID_KEY, "repository")
                    .withVariable(PlannerNode.USER_ID_KEY, "user")
                    .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString()));

            assertThat(Files.readString(workspace.resolve("value.txt")))
                    .isEqualTo("after\n");
            assertThat(result.trace())
                    .containsExactly("planner", "coder", "ops", "reviewer");
            assertThat(result.variables())
                    .containsEntry(PlannerNode.MODEL_KEY, "code-model")
                    .containsEntry(PlannerNode.KNOWLEDGE_FINGERPRINT_KEY,
                            knowledgeContext().fingerprint())
                    .containsEntry(CoderNode.MODEL_KEY, "code-model")
                    .containsEntry(CoderNode.UPDATED_FILES_KEY, "value.txt")
                    .containsEntry(CoderNode.COMMAND_NAME_KEY, "test.cat")
                    .containsEntry(OpsNode.COMMAND_KEY, "'cat' 'value.txt'")
                    .containsEntry(OpsNode.AUTHORIZATION_DECISION_KEY, "ALLOWED")
                    .containsEntry(OpsNode.AUTHORIZATION_REASON_KEY, "只读命令自动允许")
                    .containsEntry(OpsNode.EXIT_CODE_KEY, "0")
                    .containsEntry(OpsNode.STDOUT_KEY, "after\n")
                    .containsEntry(ReviewerNode.MODEL_KEY, "vision-model")
                    .containsEntry(ReviewerNode.APPROVED_KEY, "true")
                    .containsKeys(
                            PlannerNode.REQUEST_KEY,
                            PlannerNode.RESPONSE_KEY,
                            CoderNode.REQUEST_KEY,
                            CoderNode.RESPONSE_KEY,
                            ReviewerNode.REQUEST_KEY,
                            ReviewerNode.RESPONSE_KEY)
                    .doesNotContainKeys(
                            PlannerNode.ERROR_KEY,
                            CoderNode.ERROR_KEY,
                            OpsNode.ERROR_KEY,
                            ReviewerNode.ERROR_KEY);
            assertThat(result.variables().get(OpsNode.COMMAND_SHA256_KEY)).hasSize(64);
            assertThat(result.variables()).doesNotContainKey(CoderNode.COMMAND_KEY);
        }
        server.verify();
    }

    @Test
    void endsProjectKnowledgeQueryAfterPlannerWithoutExecutionTools() throws Exception {
        Files.writeString(workspace.resolve("value.txt"), "before\n");
        Path bash = Files.createFile(workspace.resolve("bash.exe"));
        try (Git ignored = Git.init().setDirectory(workspace.toFile()).call()) {
            // 知识问答使用真实 Git 工作区，但不得进入任何写入节点。
        }

        ObjectMapper objectMapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LlmClient client = new LlmClient(
                builder.build(), objectMapper, COMPLETIONS_PATH);
        ModelEndpoint endpoint = endpoint("knowledge", "code-model", client);
        ModelRouter router = new ModelRouter(Map.of(
                TaskType.CODE, List.of(endpoint),
                TaskType.VISION, List.of(endpoint),
                TaskType.QUICK_CLASSIFICATION, List.of(endpoint)));
        server.expect(times(1), requestTo(BASE_URL + COMPLETIONS_PATH))
                .andRespond(withSuccess(response(
                        objectMapper,
                        "code-model",
                        "当前仓库包含核心、沙箱和 Web 模块。"),
                        MediaType.APPLICATION_JSON));

        SandboxTerminalService terminalService = mock(SandboxTerminalService.class);
        BrowserAutomation browserAutomation = mock(BrowserAutomation.class);
        ProductionAgentProperties properties = properties(bash);
        GraphFactory factory = new ProductionGraphConfiguration().codeAgentGraph(
                properties,
                router,
                request -> {
                    throw new AssertionError("知识问答不应召回长期记忆");
                },
                request -> knowledgeContext(),
                terminalService,
                browserAutomation,
                new AstService(),
                new WorkspaceSnapshotService(50, 32_000),
                RunLogPublisher.noop(),
                objectMapper);

        try (client; StateGraph graph = factory.create()) {
            AgentState result = graph.execute(AgentState.empty()
                    .withVariable(PlannerNode.TASK_KEY, "请解释当前仓库架构")
                    .withVariable(PlannerNode.REPOSITORY_ID_KEY, "repository")
                    .withVariable(PlannerNode.USER_ID_KEY, "user")
                    .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString()));

            assertThat(result.trace()).containsExactly("planner");
            assertThat(result.variables())
                    .containsEntry(PlannerNode.ROUTE_KEY, PlannerNode.KNOWLEDGE_ROUTE)
                    .containsEntry(PlannerNode.FINAL_RESPONSE_KEY,
                            "当前仓库包含核心、沙箱和 Web 模块。")
                    .doesNotContainKeys(
                            CoderNode.UNIFIED_DIFF_KEY,
                            OpsNode.COMMAND_KEY,
                            ReviewerNode.APPROVED_KEY);
            assertThat(Files.readString(workspace.resolve("value.txt")))
                    .isEqualTo("before\n");
        }
        server.verify();
    }

    private ProductionAgentProperties properties(Path bash) {
        return new ProductionAgentProperties(
                true,
                workspace,
                "repository",
                "user",
                "",
                "PTY",
                bash.toString(),
                "python:3.12-slim",
                "/workspace",
                "",
                "",
                Duration.ofSeconds(30),
                Duration.ofSeconds(15),
                50,
                32_000,
                2,
                12,
                1_800_000,
                120_000,
                200_000,
                3,
                12_000);
    }

    private KnowledgeContext knowledgeContext() {
        return new KnowledgeContext(
                "[PROJECT_FILE] AGENTS.md",
                1,
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                32,
                false,
                List.of(new KnowledgeEvidence(
                        KnowledgeEvidenceKind.PROJECT_FILE,
                        "AGENTS.md",
                        KnowledgeEvidenceStatus.APPLIED,
                        "loaded",
                        null)));
    }

    private ModelEndpoint endpoint(String id, String model, LlmClient client) {
        return new ModelEndpoint(
                id,
                model,
                client,
                CircuitBreaker.ofDefaults(id + "-breaker"));
    }

    private String responseForRequest(ObjectMapper objectMapper, String body)
            throws IOException {
        if (body.contains("最终质量审查节点")) {
            return response(objectMapper, "vision-model",
                    "{\"approved\":true,\"summary\":\"验证通过\",\"feedback\":\"无需修改\"}");
        }
        if (body.contains("代码修改节点")) {
            String change = objectMapper.writeValueAsString(Map.of(
                    "summary", "更新 value.txt",
                    "unifiedDiff", validDiff(),
                    "commandName", "test.cat",
                    "commandArguments", List.of("value.txt")));
            return response(objectMapper, "code-model", change);
        }
        return response(objectMapper, "code-model", "修改 value.txt 并运行 cat value.txt");
    }

    private String response(ObjectMapper objectMapper, String model, String content)
            throws IOException {
        var response = objectMapper.createObjectNode();
        response.put("id", "production-agent-response");
        response.put("object", "chat.completion");
        response.put("created", 1720000000L);
        response.put("model", model);
        var choice = response.putArray("choices").addObject();
        choice.put("index", 0);
        var message = choice.putObject("message");
        message.put("role", "assistant");
        message.put("content", content);
        choice.put("finish_reason", "stop");
        return objectMapper.writeValueAsString(response);
    }

    private String validDiff() {
        return """
                diff --git a/value.txt b/value.txt
                --- a/value.txt
                +++ b/value.txt
                @@ -1 +1 @@
                -before
                +after
                """;
    }
}
