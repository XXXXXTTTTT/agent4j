package com.agent.rag.memory;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.StateGraph;
import com.agent.core.llm.ChatMessage;
import com.agent.core.llm.LlmClient;
import com.agent.core.llm.ModelEndpoint;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.TaskType;
import com.agent.core.nodes.CoderNode;
import com.agent.core.nodes.OpsNode;
import com.agent.core.nodes.PlannerNode;
import com.agent.sandbox.ast.AstService;
import com.agent.sandbox.pty.CommandResult;
import com.agent.sandbox.pty.PtyTarget;
import com.agent.sandbox.pty.TerminalCommandExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MemoryPlannerGraphTest {

    private static final String PATH = "/v1/chat/completions";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private LlmClient client;
    private MockRestServiceServer server;

    @TempDir
    Path workspace;

    @AfterEach
    void closeClient() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.verify();
        }
    }

    @Test
    void executesPlannerCoderOpsWithMemoryContext() throws Exception {
        Files.writeString(workspace.resolve("value.txt"), "before\n");
        try (Git ignored = Git.init().setDirectory(workspace.toFile()).call()) {
            // 测试只需要真实 Git 工作树。
        }
        String diff = validDiff();
        ModelEndpoint endpoint = endpoint();
        server.expect(once(), requestTo("https://memory-planner.test" + PATH))
                .andRespond(withSuccess("""
                        {"id":"planner-response","object":"chat.completion","created":1,
                         "model":"planner-model","choices":[{"index":0,
                         "message":{"role":"assistant","content":%s},
                         "finish_reason":"stop"}]}
                        """.formatted(objectMapper.valueToTree(diff)), MediaType.APPLICATION_JSON));

        PlannerNode planner = new PlannerNode(
                router(endpoint), request -> new com.agent.core.memory.MemoryContext(
                        "[ARCHITECTURE_RULE] Preserve Git diff\nUse narrow patches.", 1), 5);
        CoderNode coder = new CoderNode(new AstService());
        PtyTarget target = new PtyTarget(Files.createFile(workspace.resolve("bash.exe")), workspace);
        TerminalCommandExecutor terminal = (request, logConsumer) -> {
            assertThat(request.target()).isSameAs(target);
            try {
                return CompletableFuture.completedFuture(new CommandResult(
                        0, Files.readString(workspace.resolve("value.txt")).strip(), "", false));
            } catch (IOException exception) {
                return CompletableFuture.failedFuture(exception);
            }
        };
        OpsNode ops = new OpsNode(terminal, target, Duration.ofSeconds(30));

        try (StateGraph graph = new StateGraph(4)) {
            graph.addNode("planner", planner)
                    .addNode("coder", state -> coder.execute(state.withVariable(
                            CoderNode.UNIFIED_DIFF_KEY,
                            state.variables().get(PlannerNode.PLAN_KEY))))
                    .addNode("ops", ops)
                    .addEdge("planner", "coder")
                    .addEdge("coder", "ops")
                    .addEdge("ops", StateGraph.END)
                    .setEntryPoint("planner");

            AgentState result = graph.execute(AgentState.empty()
                    .withVariable(PlannerNode.REPOSITORY_ID_KEY, "repo")
                    .withVariable(PlannerNode.USER_ID_KEY, "user")
                    .withVariable(PlannerNode.TASK_KEY, "修改 value")
                    .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString())
                    .withVariable(OpsNode.COMMAND_KEY, "cat value.txt"));

            assertThat(result.variables())
                    .containsEntry(PlannerNode.MEMORY_CONTEXT_KEY,
                            "[ARCHITECTURE_RULE] Preserve Git diff\nUse narrow patches.")
                    .containsEntry(OpsNode.STDOUT_KEY, "after")
                    .containsEntry(OpsNode.EXIT_CODE_KEY, "0");
            assertThat(result.trace()).containsExactly("planner", "coder", "ops");
        }
    }

    private ModelRouter router(ModelEndpoint endpoint) {
        Map<TaskType, List<ModelEndpoint>> routes = new EnumMap<>(TaskType.class);
        for (TaskType type : TaskType.values()) {
            routes.put(type, List.of(endpoint));
        }
        return new ModelRouter(routes);
    }

    private ModelEndpoint endpoint() {
        String baseUrl = "https://memory-planner.test";
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new LlmClient(builder.build(), objectMapper, PATH);
        return new ModelEndpoint(
                "planner", "planner-model", client,
                CircuitBreaker.ofDefaults("memory-planner"));
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
