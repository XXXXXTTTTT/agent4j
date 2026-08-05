package com.agent.core.nodes;

import com.agent.core.engine.AgentState;
import com.agent.core.llm.LlmClient;
import com.agent.core.llm.ModelEndpoint;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.TaskType;
import com.agent.sandbox.ast.AstService;
import com.agent.sandbox.ast.WorkspaceSnapshotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CoderNodeTest {

    @TempDir
    Path workspace;

    @BeforeEach
    void initializeRepository() throws Exception {
        Files.writeString(workspace.resolve("value.txt"), "before\n");
        try (Git ignored = Git.init().setDirectory(workspace.toFile()).call()) {
            // 测试只需要真实 Git 工作树，不创建提交。
        }
    }

    @Test
    void appliesDiffAndReturnsNewImmutableState() throws Exception {
        CoderNode node = new CoderNode(new AstService());
        AgentState original = AgentState.empty()
                .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString())
                .withVariable(CoderNode.UNIFIED_DIFF_KEY, validDiff());

        AgentState result = node.execute(original);

        assertThat(result).isNotSameAs(original);
        assertThat(original.variables()).doesNotContainKey(CoderNode.UPDATED_FILES_KEY);
        assertThat(original.trace()).isEmpty();
        assertThat(Files.readString(workspace.resolve("value.txt"))).isEqualTo("after\n");
        assertThat(result.variables())
                .containsEntry(CoderNode.UPDATED_FILES_KEY, "value.txt")
                .doesNotContainKey(CoderNode.ERROR_KEY);
        assertThat(result.trace()).containsExactly("coder");
    }

    @Test
    void recordsFullStackWhenWorkspacePathIsMissing() throws Exception {
        CoderNode node = new CoderNode(new AstService());
        AgentState result = node.execute(AgentState.empty()
                .withVariable(CoderNode.UNIFIED_DIFF_KEY, validDiff()));

        assertStackTrace(result, CoderNode.WORKSPACE_PATH_KEY);
        assertThat(result.variables()).doesNotContainKey(CoderNode.UPDATED_FILES_KEY);
        assertThat(result.trace()).containsExactly("coder");
    }

    @Test
    void recordsFullStackWhenUnifiedDiffIsMissing() throws Exception {
        CoderNode node = new CoderNode(new AstService());
        AgentState result = node.execute(AgentState.empty()
                .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString()));

        assertStackTrace(result, CoderNode.UNIFIED_DIFF_KEY);
        assertThat(result.variables()).doesNotContainKey(CoderNode.UPDATED_FILES_KEY);
        assertThat(result.trace()).containsExactly("coder");
    }

    @Test
    void recordsFullStackWhenDiffConflicts() throws Exception {
        CoderNode node = new CoderNode(new AstService());
        AgentState result = node.execute(AgentState.empty()
                .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString())
                .withVariable(CoderNode.UNIFIED_DIFF_KEY, conflictingDiff()));

        assertThat(result.variables().get(CoderNode.ERROR_KEY))
                .contains("com.agent.sandbox.ast.AstServiceException")
                .contains("at ");
        assertThat(result.variables()).doesNotContainKey(CoderNode.UPDATED_FILES_KEY);
        assertThat(Files.readString(workspace.resolve("value.txt"))).isEqualTo("before\n");
        assertThat(result.trace()).containsExactly("coder");
    }

    @Test
    void rejectsNullAstService() {
        assertThatThrownBy(() -> new CoderNode(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void generatesStrictJsonDiffAndCommandBeforeApplyingPatch() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder().baseUrl("https://coder.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LlmClient client = new LlmClient(
                builder.build(), objectMapper, "/v1/chat/completions");
        ModelEndpoint endpoint = new ModelEndpoint(
                "coder-endpoint",
                "coder-model",
                client,
                CircuitBreaker.ofDefaults("coder-breaker"));
        ModelRouter router = new ModelRouter(java.util.Map.of(
                TaskType.CODE, java.util.List.of(endpoint),
                TaskType.VISION, java.util.List.of(endpoint),
                TaskType.QUICK_CLASSIFICATION, java.util.List.of(endpoint)));
        server.expect(once(), requestTo("https://coder.test/v1/chat/completions"))
                .andExpect(content().string(containsString("value.txt")))
                .andRespond(withSuccess(coderResponse(objectMapper), MediaType.APPLICATION_JSON));

        try (client) {
            CoderNode node = new CoderNode(
                    new AstService(),
                    router,
                    objectMapper,
                    new WorkspaceSnapshotService(10, 4096));
            AgentState result = node.execute(AgentState.empty()
                    .withVariable(PlannerNode.TASK_KEY, "把 value.txt 改成 after")
                    .withVariable(PlannerNode.PLAN_KEY, "修改 value.txt 并运行测试")
                    .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString()));

            assertThat(result.variables()).as("coder variables").doesNotContainKey(CoderNode.ERROR_KEY);
            assertThat(Files.readString(workspace.resolve("value.txt"))).isEqualTo("after\n");
            assertThat(result.variables())
                    .containsEntry(CoderNode.COMMAND_KEY, "cat value.txt")
                    .containsEntry(CoderNode.UPDATED_FILES_KEY, "value.txt")
                    .containsEntry(CoderNode.MODEL_KEY, "coder-model")
                    .containsKey(CoderNode.REQUEST_KEY)
                    .containsKey(CoderNode.RESPONSE_KEY)
                    .doesNotContainKey(CoderNode.ERROR_KEY);
            assertThat(result.trace()).containsExactly("coder");
        }
        server.verify();
    }

    private String coderResponse(ObjectMapper objectMapper) throws Exception {
        var response = objectMapper.createObjectNode();
        response.put("id", "coder-response");
        response.put("object", "chat.completion");
        response.put("created", 1720000000L);
        response.put("model", "coder-model");
        var choice = response.putArray("choices").addObject();
        choice.put("index", 0);
        var message = choice.putObject("message");
        message.put("role", "assistant");
        message.put("content", objectMapper.writeValueAsString(java.util.Map.of(
                "summary", "修改值文件",
                "unifiedDiff", validDiff(),
                "command", "cat value.txt")));
        choice.put("finish_reason", "stop");
        return objectMapper.writeValueAsString(response);
    }

    private void assertStackTrace(AgentState result, String missingKey) {
        assertThat(result.variables().get(CoderNode.ERROR_KEY))
                .contains("java.lang.IllegalArgumentException")
                .contains(missingKey)
                .contains("at ");
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

    private String conflictingDiff() {
        return """
                diff --git a/value.txt b/value.txt
                --- a/value.txt
                +++ b/value.txt
                @@ -1 +1 @@
                -missing
                +after
                """;
    }
}
