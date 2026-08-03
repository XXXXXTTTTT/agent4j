package com.agent.core.nodes;

import com.agent.core.engine.AgentState;
import com.agent.core.memory.MemoryContext;
import com.agent.core.memory.MemoryContextProvider;
import com.agent.core.memory.MemoryContextRequest;
import com.agent.core.llm.ChatMessage;
import com.agent.core.llm.LlmClient;
import com.agent.core.llm.ModelEndpoint;
import com.agent.core.llm.ModelRequest;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.TaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PlannerNodeTest {

    private static final String PATH = "/v1/chat/completions";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<LlmClient> clients = new ArrayList<>();
    private final List<MockRestServiceServer> servers = new ArrayList<>();

    @AfterEach
    void closeClients() {
        clients.forEach(LlmClient::close);
        servers.forEach(MockRestServiceServer::verify);
    }

    @Test
    void injectsMemoryIntoCodePlanningPromptAndStoresModel() {
        Endpoint endpoint = endpoint();
        endpoint.server().expect(once(), requestTo(endpoint.baseUrl() + PATH))
                .andExpect(content().json("""
                        {
                          "model":"planner-model",
                          "messages":[
                            {"role":"system"},
                            {"role":"user","content":"任务:\n修复超时清理\n\n长期记忆上下文:\n[ BAD_CASE ] 清理\n必须删除容器"}
                          ],
                          "tools":[],"temperature":0.0,"stream":false
                        }
                        """, false))
                .andRespond(withSuccess("""
                        {"id":"plan-response","object":"chat.completion","created":1,
                         "model":"planner-model","choices":[{"index":0,
                         "message":{"role":"assistant","content":"step 1: clean containers"},
                         "finish_reason":"stop"}]}
                        """, MediaType.APPLICATION_JSON));
        RecordingProvider provider = new RecordingProvider(
                new MemoryContext("[ BAD_CASE ] 清理\n必须删除容器", 1));
        PlannerNode node = new PlannerNode(router(endpoint), provider, 7);

        AgentState result = node.execute(AgentState.empty()
                .withVariable(PlannerNode.REPOSITORY_ID_KEY, "repo")
                .withVariable(PlannerNode.USER_ID_KEY, "user")
                .withVariable(PlannerNode.TASK_KEY, "修复超时清理"));

        assertThat(provider.request).isEqualTo(
                new MemoryContextRequest("repo", "user", "修复超时清理", 7));
        assertThat(result.variables())
                .containsEntry(PlannerNode.MEMORY_CONTEXT_KEY, "[ BAD_CASE ] 清理\n必须删除容器")
                .containsEntry(PlannerNode.PLAN_KEY, "step 1: clean containers")
                .containsEntry(PlannerNode.MODEL_KEY, "planner-model")
                .doesNotContainKey(PlannerNode.ERROR_KEY);
        assertThat(result.trace()).containsExactly("planner");
    }

    @Test
    void preservesFullMemoryFailureStackAndDoesNotWritePlan() {
        MemoryContextProvider provider = request -> {
            throw new IllegalStateException("memory unavailable");
        };
        PlannerNode node = new PlannerNode(routerWithoutRequests(), provider, 3);

        AgentState result = node.execute(AgentState.empty()
                .withVariable(PlannerNode.REPOSITORY_ID_KEY, "repo")
                .withVariable(PlannerNode.USER_ID_KEY, "user")
                .withVariable(PlannerNode.TASK_KEY, "task"));

        assertThat(result.variables()).containsKey(PlannerNode.ERROR_KEY)
                .doesNotContainKey(PlannerNode.PLAN_KEY);
        assertThat(result.variables().get(PlannerNode.ERROR_KEY))
                .contains("memory unavailable");
        assertThat(result.trace()).containsExactly("planner");
    }

    @Test
    void rejectsMissingPlannerInputs() {
        PlannerNode node = new PlannerNode(
                routerWithoutRequests(), request -> new MemoryContext("", 0), 3);

        AgentState result = node.execute(AgentState.empty());

        assertThat(result.variables().get(PlannerNode.ERROR_KEY))
                .contains("planner.repositoryId")
                .contains("IllegalArgumentException");
        assertThat(result.trace()).containsExactly("planner");
    }

    private ModelRouter router(Endpoint endpoint) {
        Map<TaskType, List<ModelEndpoint>> routes = new EnumMap<>(TaskType.class);
        for (TaskType type : TaskType.values()) {
            routes.put(type, List.of(endpoint.modelEndpoint()));
        }
        return new ModelRouter(routes);
    }

    private ModelRouter routerWithoutRequests() {
        Endpoint endpoint = endpoint();
        return router(endpoint);
    }

    private Endpoint endpoint() {
        String baseUrl = "https://planner.test/" + clients.size();
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LlmClient client = new LlmClient(builder.build(), objectMapper, PATH);
        clients.add(client);
        servers.add(server);
        ModelEndpoint modelEndpoint = new ModelEndpoint(
                "planner", "planner-model", client,
                CircuitBreaker.ofDefaults("planner-" + clients.size()));
        return new Endpoint(modelEndpoint, baseUrl, server);
    }

    private record Endpoint(
            ModelEndpoint modelEndpoint,
            String baseUrl,
            MockRestServiceServer server) {
    }

    private static final class RecordingProvider implements MemoryContextProvider {
        private final MemoryContext response;
        private MemoryContextRequest request;

        private RecordingProvider(MemoryContext response) {
            this.response = response;
        }

        @Override
        public MemoryContext recall(MemoryContextRequest request) {
            this.request = request;
            return response;
        }
    }
}
