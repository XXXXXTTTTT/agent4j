package com.agent.core.nodes;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.StateGraph;
import com.agent.core.llm.LlmClient;
import com.agent.core.llm.ModelEndpoint;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.TaskType;
import com.agent.sandbox.ast.AstService;
import com.agent.sandbox.browser.BrowserAutomation;
import com.agent.sandbox.browser.BrowserScreenshot;
import com.agent.sandbox.browser.NavigationResult;
import com.agent.sandbox.pty.CommandResult;
import com.agent.sandbox.pty.PtyTarget;
import com.agent.sandbox.pty.TerminalCommandExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CoderOpsReviewerGraphTest {

    private static final String BASE_URL = "https://graph-vision.test";
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    @TempDir
    Path workspace;

    @Test
    void appliesDiffRunsCommandAndReviewsEvidenceInOneGraph() throws Exception {
        Files.writeString(workspace.resolve("value.txt"), "before\n");
        try (Git ignored = Git.init().setDirectory(workspace.toFile()).call()) {
            // 测试只需要真实 Git 工作树，不创建提交。
        }
        PtyTarget target = new PtyTarget(
                Files.createFile(workspace.resolve("bash.exe")), workspace);
        TerminalCommandExecutor terminal = terminalReadingUpdatedFile();
        CoderNode coderNode = new CoderNode(new AstService());
        OpsNode opsNode = new OpsNode(terminal, target, Duration.ofSeconds(30));
        FixedBrowserAutomation browser = new FixedBrowserAutomation();
        ObjectMapper objectMapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LlmClient client = new LlmClient(
                builder.build(), objectMapper, CHAT_COMPLETIONS_PATH);
        ModelEndpoint endpoint = new ModelEndpoint(
                "graph-vision-endpoint",
                "graph-vision-model",
                client,
                CircuitBreaker.ofDefaults("graph-reviewer"));
        ModelRouter router = new ModelRouter(Map.of(
                TaskType.CODE, List.of(endpoint),
                TaskType.VISION, List.of(endpoint),
                TaskType.QUICK_CLASSIFICATION, List.of(endpoint)));
        ReviewerNode reviewerNode = new ReviewerNode(
                browser, router, objectMapper, Duration.ofSeconds(15));
        server.expect(once(), requestTo(BASE_URL + CHAT_COMPLETIONS_PATH))
                .andExpect(content().string(containsString("after")))
                .andExpect(content().string(containsString("data:image/png;base64,AQID")))
                .andRespond(withSuccess(reviewResponse(objectMapper),
                        MediaType.APPLICATION_JSON));

        try (client; StateGraph graph = new StateGraph(5)) {
            graph.addNode("coder", coderNode)
                    .addNode("ops", opsNode)
                    .addNode("reviewer", reviewerNode)
                    .addEdge("coder", "ops")
                    .addEdge("ops", "reviewer")
                    .addEdge("reviewer", StateGraph.END)
                    .setEntryPoint("coder");

            AgentState result = graph.execute(AgentState.empty()
                    .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString())
                    .withVariable(CoderNode.UNIFIED_DIFF_KEY, validDiff())
                    .withVariable(OpsNode.COMMAND_KEY, "cat value.txt")
                    .withVariable(ReviewerNode.URL_KEY, "https://application.test/review"));

            assertThat(Files.readString(workspace.resolve("value.txt"))).isEqualTo("after\n");
            assertThat(result.variables())
                    .containsEntry(CoderNode.UPDATED_FILES_KEY, "value.txt")
                    .containsEntry(OpsNode.STDOUT_KEY, "after")
                    .containsEntry(ReviewerNode.APPROVED_KEY, "true")
                    .containsEntry(ReviewerNode.MODEL_KEY, "graph-vision-model")
                    .doesNotContainKeys(
                            CoderNode.ERROR_KEY,
                            OpsNode.ERROR_KEY,
                            ReviewerNode.ERROR_KEY);
            assertThat(result.trace()).containsExactly("coder", "ops", "reviewer");
        }
        server.verify();
    }

    private TerminalCommandExecutor terminalReadingUpdatedFile() {
        return (request, logConsumer) -> {
            try {
                return CompletableFuture.completedFuture(new CommandResult(
                        0,
                        Files.readString(workspace.resolve("value.txt")).strip(),
                        "",
                        false));
            } catch (IOException exception) {
                return CompletableFuture.failedFuture(exception);
            }
        };
    }

    private String reviewResponse(ObjectMapper objectMapper) throws Exception {
        var response = objectMapper.createObjectNode();
        response.put("id", "graph-review-response");
        response.put("object", "chat.completion");
        response.put("created", 1720000000L);
        response.put("model", "graph-vision-model");
        var choice = response.putArray("choices").addObject();
        choice.put("index", 0);
        var message = choice.putObject("message");
        message.put("role", "assistant");
        message.put("content",
                "{\"approved\":true,\"summary\":\"闭环正常\",\"feedback\":\"无需修改\"}");
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

    private static final class FixedBrowserAutomation implements BrowserAutomation {

        @Override
        public CompletableFuture<NavigationResult> navigate(URI url, Duration timeout) {
            return CompletableFuture.completedFuture(new NavigationResult(
                    url, url, OptionalInt.of(200)));
        }

        @Override
        public CompletableFuture<Void> click(String selector, Duration timeout) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<String> extractDom() {
            return CompletableFuture.completedFuture(
                    "<html><body>after</body></html>");
        }

        @Override
        public CompletableFuture<BrowserScreenshot> screenshot(Duration timeout) {
            return CompletableFuture.completedFuture(
                    new BrowserScreenshot(new byte[] {1, 2, 3}, "image/png"));
        }

        @Override
        public void close() {
        }
    }
}
