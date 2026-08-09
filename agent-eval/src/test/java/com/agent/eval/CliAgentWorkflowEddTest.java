package com.agent.eval;

import com.agent.core.cli.CliApprovalInterruptPolicy;
import com.agent.core.cli.CliCommandCatalog;
import com.agent.core.cli.CliCommandDefinition;
import com.agent.core.cli.CliRiskLevel;
import com.agent.core.engine.AgentState;
import com.agent.core.engine.StateGraph;
import com.agent.core.intent.RequiredCapability;
import com.agent.core.llm.LlmClient;
import com.agent.core.llm.ModelEndpoint;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.TaskType;
import com.agent.core.nodes.CoderNode;
import com.agent.core.nodes.OpsNode;
import com.agent.core.nodes.PlannerNode;
import com.agent.core.nodes.ReviewerNode;
import com.agent.core.tool.DefaultToolRegistry;
import com.agent.core.tool.builtin.CodePatchTool;
import com.agent.sandbox.ast.AstService;
import com.agent.sandbox.ast.WorkspaceSnapshotService;
import com.agent.sandbox.browser.BrowserAutomation;
import com.agent.sandbox.pty.PtyTarget;
import com.agent.sandbox.pty.SandboxTerminalService;
import com.agent.sandbox.pty.TerminalCommandExecutor;
import com.agent.sandbox.pty.TerminalLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 对真实 Git/JGit/PTY 代码修复循环执行确定性 EDD。 */
@Tag("edd")
class CliAgentWorkflowEddTest {

    private static final Path BASH_EXECUTABLE = Path.of("D:/Git/bin/bash.exe");
    private static final String BASE_URL = "https://cli-agent-edd.test";
    private static final String COMPLETIONS_PATH = "/v1/chat/completions";
    private static final Set<String> REPORT_FIELDS = Set.of(
            "taskId", "status", "attempts", "updatedFiles", "commandSha256",
            "terminalCalls", "passed");

    @TempDir
    Path workspace;

    @Test
    void repairsFailedRealPtyVerificationAndWritesFixedReport() throws Exception {
        assumeTrue(Files.isRegularFile(BASH_EXECUTABLE),
                "缺少精确 Git Bash 路径，跳过 CLI Agent EDD");
        initializeWorkspace();
        ObjectMapper mapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LlmClient client = new LlmClient(builder.build(), mapper, COMPLETIONS_PATH);
        ModelEndpoint code = endpoint("code", "code-model", client);
        ModelEndpoint vision = endpoint("vision", "vision-model", client);
        ModelRouter router = new ModelRouter(Map.of(
                TaskType.CODE, List.of(code),
                TaskType.VISION, List.of(vision),
                TaskType.QUICK_CLASSIFICATION, List.of(code)));
        server.expect(times(4), requestTo(BASE_URL + COMPLETIONS_PATH))
                .andRespond(request -> withSuccess(
                        responseForRequest(
                                mapper,
                                ((MockClientHttpRequest) request).getBodyAsString()),
                        MediaType.APPLICATION_JSON).createResponse(request));

        AstService astService = new AstService();
        DefaultToolRegistry tools = new DefaultToolRegistry();
        tools.register(CodePatchTool.definition(astService, mapper));
        PtyTarget target = new PtyTarget(BASH_EXECUTABLE, workspace);
        CliCommandCatalog catalog = new CliCommandCatalog(List.of(
                new CliCommandDefinition(
                        "test.value",
                        "bash",
                        List.of("verify.sh"),
                        CliRiskLevel.READ_ONLY,
                        Set.of(RequiredCapability.TERMINAL))));
        CliApprovalInterruptPolicy policy = new CliApprovalInterruptPolicy(
                catalog, target, Duration.ofSeconds(20), mapper);
        AtomicInteger terminalCalls = new AtomicInteger();
        List<TerminalLog> terminalLogs = new ArrayList<>();

        try (client;
             tools;
             SandboxTerminalService terminal = new SandboxTerminalService();
             StateGraph graph = graph(
                     router,
                     mapper,
                     astService,
                     tools,
                     policy,
                     countingTerminal(terminal, terminalCalls, terminalLogs))) {
            AgentState result = graph.execute(initialState());

            assertThat(result.trace()).containsExactly(
                    "planner", "coder", "ops", "reviewer",
                    "coder", "ops", "reviewer");
            assertThat(result.variables())
                    .containsEntry(CoderNode.ATTEMPT_KEY, "2")
                    .containsEntry(CoderNode.UPDATED_FILES_KEY, "value.txt")
                    .containsEntry(OpsNode.EXIT_CODE_KEY, "0")
                    .containsEntry(ReviewerNode.APPROVED_KEY, "true")
                    .doesNotContainKeys(
                            CoderNode.ERROR_KEY,
                            OpsNode.ERROR_KEY,
                            ReviewerNode.ERROR_KEY);
            assertThat(Files.readString(workspace.resolve("value.txt"))).isEqualTo("after\n");
            assertThat(terminalCalls).hasValue(2);
            assertThat(terminalLogs)
                    .anySatisfy(log -> assertThat(log.text()).contains("value=broken"))
                    .anySatisfy(log -> assertThat(log.text()).contains("value=after"));
            assertThat(result.variables().get(OpsNode.STDOUT_KEY)).contains("value=after");
            writeAndVerifyReport(mapper, result, terminalCalls.get());
        }
        server.verify();
    }

    private StateGraph graph(
            ModelRouter router,
            ObjectMapper mapper,
            AstService astService,
            DefaultToolRegistry tools,
            CliApprovalInterruptPolicy policy,
            TerminalCommandExecutor terminal) {
        CoderNode coder = new CoderNode(
                astService,
                router,
                mapper,
                new WorkspaceSnapshotService(20, 32_000),
                tools);
        OpsNode ops = new OpsNode(terminal, policy, event -> { });
        ReviewerNode reviewer = new ReviewerNode(
                mock(BrowserAutomation.class),
                router,
                mapper,
                Duration.ofSeconds(10));
        return new StateGraph(10, policy)
                .addNode("planner", state -> state
                        .withVariable(PlannerNode.PLAN_KEY,
                                "修改 value.txt 并运行受治理验证命令")
                        .withTraceEntry("planner"))
                .addNode("coder", coder)
                .addNode("ops", ops)
                .addNode("reviewer", reviewer)
                .setEntryPoint("planner")
                .addEdge("planner", "coder")
                .addEdge("coder", "ops")
                .addEdge("ops", "reviewer")
                .addConditionalEdges(
                        "reviewer",
                        state -> "false".equals(
                                state.variables().get(ReviewerNode.APPROVED_KEY))
                                ? "repair" : "finish",
                        Map.of("repair", "coder", "finish", StateGraph.END));
    }

    private TerminalCommandExecutor countingTerminal(
            SandboxTerminalService terminal,
            AtomicInteger calls,
            List<TerminalLog> observedLogs) {
        return (request, logConsumer) -> {
            calls.incrementAndGet();
            return terminal.execute(request, log -> {
                observedLogs.add(log);
                logConsumer.accept(log);
            });
        };
    }

    private AgentState initialState() {
        return AgentState.empty()
                .withVariable(PlannerNode.TASK_KEY, "把 value.txt 修复为 after 并验证")
                .withVariable(PlannerNode.USER_ID_KEY, "edd-user")
                .withVariable(PlannerNode.KNOWLEDGE_CONTEXT_KEY,
                        "规则: 只能修改 value.txt，并使用 test.value 验证")
                .withVariable(PlannerNode.KNOWLEDGE_FINGERPRINT_KEY,
                        "edd-knowledge-fingerprint")
                .withVariable(PlannerNode.KNOWLEDGE_SOURCES_KEY, "1")
                .withVariable(PlannerNode.REQUIRED_CAPABILITIES_KEY,
                        "CODE_READ,CODE_WRITE,TERMINAL")
                .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString());
    }

    private void initializeWorkspace() throws Exception {
        Files.writeString(workspace.resolve("value.txt"), "before\n");
        Files.writeString(workspace.resolve("verify.sh"), """
                #!/usr/bin/env bash
                set -u
                value=$(cat value.txt)
                printf '\033[36mvalue=%s\033[0m\n' "$value"
                [[ "$value" == "after" ]]
                """);
        try (Git ignored = Git.init().setDirectory(workspace.toFile()).call()) {
            // EDD 使用真实 Git 工作树应用两次连续 Unified Diff。
        }
    }

    private String responseForRequest(ObjectMapper mapper, String body) throws IOException {
        if (body.contains("最终质量审查节点")) {
            boolean approved = body.contains("ops.exitCode=\\n0");
            String decision = mapper.writeValueAsString(Map.of(
                    "approved", approved,
                    "summary", approved ? "真实 PTY 验证通过" : "真实 PTY 验证失败",
                    "feedback", approved ? "无需修改" : "value.txt 尚未等于 after"));
            return completion(mapper, "vision-model", decision);
        }
        boolean repair = body.contains("ops.exitCode:\\n1");
        String change = mapper.writeValueAsString(Map.of(
                "summary", repair ? "修复值文件" : "制造首轮失败以验证修复循环",
                "unifiedDiff", repair ? repairDiff() : firstDiff(),
                "commandName", "test.value",
                "commandArguments", List.of()));
        return completion(mapper, "code-model", change);
    }

    private String completion(ObjectMapper mapper, String model, String content)
            throws IOException {
        var response = mapper.createObjectNode();
        response.put("id", "cli-agent-edd-response");
        response.put("object", "chat.completion");
        response.put("created", 1720000000L);
        response.put("model", model);
        var choice = response.putArray("choices").addObject();
        choice.put("index", 0);
        var message = choice.putObject("message");
        message.put("role", "assistant");
        message.put("content", content);
        choice.put("finish_reason", "stop");
        return mapper.writeValueAsString(response);
    }

    private ModelEndpoint endpoint(String id, String model, LlmClient client) {
        return new ModelEndpoint(
                id,
                model,
                client,
                CircuitBreaker.ofDefaults("cli-agent-edd-" + id));
    }

    private void writeAndVerifyReport(
            ObjectMapper mapper,
            AgentState result,
            int terminalCalls) throws Exception {
        EddResult scenario = new EddResult(
                "cli-agent.repair-loop",
                "COMPLETED",
                Integer.parseInt(result.variables().get(CoderNode.ATTEMPT_KEY)),
                result.variables().get(CoderNode.UPDATED_FILES_KEY),
                result.variables().get(OpsNode.COMMAND_SHA256_KEY),
                terminalCalls,
                true);
        Path report = Path.of("target", "edd", "cli-agent-workflow-edd.json");
        Files.createDirectories(report.getParent());
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(report.toFile(), Map.of("scenarios", List.of(scenario)));
        JsonNode reportJson = mapper.readTree(report.toFile());
        JsonNode written = reportJson.path("scenarios").get(0);
        Set<String> fields = new LinkedHashSet<>();
        written.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrderElementsOf(REPORT_FIELDS);
        assertThat(written.path("attempts").asInt()).isEqualTo(2);
        assertThat(written.path("commandSha256").asText()).matches("[0-9a-f]{64}");
        assertThat(written.path("terminalCalls").asInt()).isEqualTo(2);
        assertThat(written.path("passed").asBoolean()).isTrue();
    }

    private String firstDiff() {
        return """
                diff --git a/value.txt b/value.txt
                --- a/value.txt
                +++ b/value.txt
                @@ -1 +1 @@
                -before
                +broken
                """;
    }

    private String repairDiff() {
        return """
                diff --git a/value.txt b/value.txt
                --- a/value.txt
                +++ b/value.txt
                @@ -1 +1 @@
                -broken
                +after
                """;
    }

    private record EddResult(
            String taskId,
            String status,
            int attempts,
            String updatedFiles,
            String commandSha256,
            int terminalCalls,
            boolean passed) {
    }
}
