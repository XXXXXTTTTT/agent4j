package com.agent.core.nodes;

import com.agent.core.engine.AgentState;
import com.agent.core.intent.RequiredCapability;
import com.agent.core.intent.TaskComplexity;
import com.agent.core.intent.TaskDecision;
import com.agent.core.intent.TaskKind;
import com.agent.core.intent.TaskRoute;
import com.agent.core.knowledge.KnowledgeContext;
import com.agent.core.knowledge.KnowledgeContextProvider;
import com.agent.core.knowledge.KnowledgeContextRequest;
import com.agent.core.knowledge.KnowledgeEvidence;
import com.agent.core.knowledge.KnowledgeEvidenceKind;
import com.agent.core.knowledge.KnowledgeEvidenceStatus;
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
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @TempDir
    Path workspace;

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
                            {"role":"user","content":"任务:\n修复超时清理\n\n长期记忆上下文:\n[ BAD_CASE ] 清理\n必须删除容器\n\n项目知识上下文:\n"}
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
                .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString())
                .withVariable(PlannerNode.TASK_KEY, "修复超时清理"));

        assertThat(provider.request).isEqualTo(
                new MemoryContextRequest("repo", "user", "修复超时清理", 7));
        assertThat(result.variables())
                .containsEntry(PlannerNode.MEMORY_CONTEXT_KEY, "[ BAD_CASE ] 清理\n必须删除容器")
                .containsEntry(PlannerNode.PLAN_KEY, "step 1: clean containers")
                .containsEntry(PlannerNode.REQUEST_KEY,
                        "任务:\n修复超时清理\n\n长期记忆上下文:\n[ BAD_CASE ] 清理\n必须删除容器"
                                + "\n\n项目知识上下文:\n")
                .containsEntry(PlannerNode.RESPONSE_KEY, "step 1: clean containers")
                .containsEntry(PlannerNode.MODEL_KEY, "planner-model")
                .doesNotContainKey(PlannerNode.ERROR_KEY);
        assertThat(result.trace()).containsExactly("planner");
    }

    @Test
    void answersHighConfidenceQuestionWithoutPlanningCodeWork() {
        Endpoint endpoint = endpoint();
        endpoint.server().expect(once(), requestTo(endpoint.baseUrl() + PATH))
                .andExpect(content().json("""
                        {
                          "model":"planner-model",
                          "messages":[
                            {"role":"system"},
                            {"role":"user","content":"你是什么模型"}
                          ],
                          "tools":[],"temperature":0.0,"stream":false
                        }
                        """, false))
                .andRespond(withSuccess("""
                        {"id":"answer-response","object":"chat.completion","created":1,
                         "model":"planner-model","choices":[{"index":0,
                         "message":{"role":"assistant","content":"我是一个 AI 助手。"},
                         "finish_reason":"stop"}]}
                        """, MediaType.APPLICATION_JSON));

        PlannerNode node = new PlannerNode(
                router(endpoint), request -> {
                    throw new AssertionError("快速问答不应召回代码仓库记忆");
                }, 7);

        AgentState result = node.execute(AgentState.empty()
                .withVariable(PlannerNode.TASK_KEY, "你是什么模型"));

        assertThat(result.variables())
                .containsEntry(PlannerNode.ROUTE_KEY, PlannerNode.CHAT_ROUTE)
                .containsEntry(PlannerNode.FINAL_RESPONSE_KEY, "我是一个 AI 助手。")
                .doesNotContainKey(PlannerNode.PLAN_KEY)
                .doesNotContainKey(PlannerNode.ERROR_KEY);
        assertThat(result.trace()).containsExactly("planner");
    }

    @Test
    void includesPreviousMessagesInChatContextAndAppendsCurrentExchange() {
        Endpoint endpoint = endpoint();
        endpoint.server().expect(once(), requestTo(endpoint.baseUrl() + PATH))
                .andExpect(content().json("""
                        {
                          "model":"planner-model",
                          "messages":[
                            {"role":"system"},
                            {"role":"user","content":"我住在南昌"},
                            {"role":"assistant","content":"已记住"},
                            {"role":"user","content":"我住在哪里？"}
                          ],
                          "tools":[],"temperature":0.0,"stream":false
                        }
                        """, false))
                .andRespond(withSuccess("""
                        {"id":"answer-response","object":"chat.completion","created":1,
                         "model":"planner-model","choices":[{"index":0,
                         "message":{"role":"assistant","content":"你住在南昌。"},
                         "finish_reason":"stop"}]}
                        """, MediaType.APPLICATION_JSON));

        PlannerNode node = new PlannerNode(
                router(endpoint), request -> {
                    throw new AssertionError("快速问答不应召回代码仓库记忆");
                }, 7);

        AgentState result = node.execute(new AgentState(
                List.of(ChatMessage.user("我住在南昌"), ChatMessage.assistant("已记住")),
                Map.of(PlannerNode.TASK_KEY, "我住在哪里？"),
                List.of()));

        assertThat(result.messages())
                .extracting(message -> ((ChatMessage.TextContent) message.content()).text())
                .containsExactly("我住在南昌", "已记住", "我住在哪里？", "你住在南昌。");
    }

    @Test
    void routesExplicitModifyRequestDirectlyToCodePlanning() {
        Endpoint endpoint = endpoint();
        endpoint.server().expect(once(), requestTo(endpoint.baseUrl() + PATH))
                .andExpect(content().json("""
                        {
                          "model":"planner-model",
                          "messages":[
                            {"role":"system"},
                            {"role":"user","content":"任务:\n修改 value\n\n长期记忆上下文:\nUse narrow patches.\n\n项目知识上下文:\n"}
                          ],
                          "tools":[],"temperature":0.0,"stream":false
                        }
                        """, false))
                .andRespond(withSuccess("""
                        {"id":"plan-response","object":"chat.completion","created":1,
                         "model":"planner-model","choices":[{"index":0,
                         "message":{"role":"assistant","content":"apply the narrow patch"},
                         "finish_reason":"stop"}]}
                        """, MediaType.APPLICATION_JSON));
        RecordingProvider provider = new RecordingProvider(
                new MemoryContext("Use narrow patches.", 1));
        PlannerNode node = new PlannerNode(router(endpoint), provider, 5);

        AgentState result = node.execute(AgentState.empty()
                .withVariable(PlannerNode.REPOSITORY_ID_KEY, "repo")
                .withVariable(PlannerNode.USER_ID_KEY, "user")
                .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString())
                .withVariable(PlannerNode.TASK_KEY, "修改 value"));

        assertThat(provider.request).isEqualTo(
                new MemoryContextRequest("repo", "user", "修改 value", 5));
        assertThat(result.variables())
                .containsEntry(PlannerNode.ROUTE_KEY, PlannerNode.AGENT_ROUTE)
                .containsEntry(PlannerNode.PLAN_KEY, "apply the narrow patch")
                .doesNotContainKeys(PlannerNode.FINAL_RESPONSE_KEY, PlannerNode.ERROR_KEY);
    }

    @Test
    void semanticallyRoutesAmbiguousConversationBeforeUsingCodePlanning() {
        Endpoint endpoint = endpoint();
        endpoint.server().expect(once(), requestTo(endpoint.baseUrl() + PATH))
                .andExpect(content().json("""
                        {
                          "model":"planner-model",
                          "messages":[
                            {"role":"system"},
                            {"role":"user","content":"聊聊你最擅长的事情"}
                          ],
                          "tools":[],"temperature":0.0,"stream":false
                        }
                        """, false))
                .andRespond(withSuccess("""
                        {"id":"route-response","object":"chat.completion","created":1,
                         "model":"planner-model","choices":[{"index":0,
                         "message":{"role":"assistant","content":"chat"},
                         "finish_reason":"stop"}]}
                        """, MediaType.APPLICATION_JSON));
        endpoint.server().expect(once(), requestTo(endpoint.baseUrl() + PATH))
                .andExpect(content().json("""
                        {
                          "model":"planner-model",
                          "messages":[
                            {"role":"system"},
                            {"role":"user","content":"聊聊你最擅长的事情"}
                          ],
                          "tools":[],"temperature":0.0,"stream":false
                        }
                        """, false))
                .andRespond(withSuccess("""
                        {"id":"answer-response","object":"chat.completion","created":1,
                         "model":"planner-model","choices":[{"index":0,
                         "message":{"role":"assistant","content":"我擅长分析代码与协助工程实现。"},
                         "finish_reason":"stop"}]}
                        """, MediaType.APPLICATION_JSON));

        PlannerNode node = new PlannerNode(
                router(endpoint), request -> {
                    throw new AssertionError("聊天语义路由不应召回代码仓库记忆");
                }, 7);

        AgentState result = node.execute(AgentState.empty()
                .withVariable(PlannerNode.TASK_KEY, "聊聊你最擅长的事情"));

        assertThat(result.variables())
                .containsEntry(PlannerNode.ROUTE_KEY, PlannerNode.CHAT_ROUTE)
                .containsEntry(PlannerNode.FINAL_RESPONSE_KEY, "我擅长分析代码与协助工程实现。")
                .doesNotContainKeys(PlannerNode.PLAN_KEY, PlannerNode.ERROR_KEY);
    }

    @Test
    void normalizesRouteExplanationAndAnswersChat() {
        Endpoint endpoint = endpoint();
        endpoint.server().expect(once(), requestTo(endpoint.baseUrl() + PATH))
                .andRespond(withSuccess("""
                        {"id":"route-response","object":"chat.completion","created":1,
                         "model":"planner-model","choices":[{"index":0,
                         "message":{"role":"assistant","content":"chat，因为这是自然语言问题"},
                         "finish_reason":"stop"}]}
                        """, MediaType.APPLICATION_JSON));
        endpoint.server().expect(once(), requestTo(endpoint.baseUrl() + PATH))
                .andRespond(withSuccess("""
                        {"id":"answer-response","object":"chat.completion","created":1,
                         "model":"planner-model","choices":[{"index":0,
                         "message":{"role":"assistant","content":"这是一个自然语言回答。"},
                         "finish_reason":"stop"}]}
                        """, MediaType.APPLICATION_JSON));

        PlannerNode node = new PlannerNode(
                router(endpoint), request -> {
                    throw new AssertionError("聊天语义路由不应召回代码仓库记忆");
                }, 7);

        AgentState result = node.execute(AgentState.empty()
                .withVariable(PlannerNode.TASK_KEY, "按天气规划"));

        assertThat(result.variables())
                .containsEntry(PlannerNode.ROUTE_KEY, PlannerNode.CHAT_ROUTE)
                .containsEntry(PlannerNode.FINAL_RESPONSE_KEY, "这是一个自然语言回答。")
                .doesNotContainKey(PlannerNode.ERROR_KEY);
    }

    @Test
    void normalizesMarkdownRouteOutput() {
        Endpoint endpoint = endpoint();
        endpoint.server().expect(once(), requestTo(endpoint.baseUrl() + PATH))
                .andRespond(withSuccess("""
                        {"id":"route-response","object":"chat.completion","created":1,
                         "model":"planner-model","choices":[{"index":0,
                         "message":{"role":"assistant","content":"```chat```"},
                         "finish_reason":"stop"}]}
                        """, MediaType.APPLICATION_JSON));
        endpoint.server().expect(once(), requestTo(endpoint.baseUrl() + PATH))
                .andRespond(withSuccess("""
                        {"id":"answer-response","object":"chat.completion","created":1,
                         "model":"planner-model","choices":[{"index":0,
                         "message":{"role":"assistant","content":"围栏回答"},
                         "finish_reason":"stop"}]}
                        """, MediaType.APPLICATION_JSON));

        PlannerNode node = new PlannerNode(
                router(endpoint), request -> {
                    throw new AssertionError("聊天语义路由不应召回代码仓库记忆");
                }, 7);

        AgentState result = node.execute(AgentState.empty()
                .withVariable(PlannerNode.TASK_KEY, "按天气规划"));

        assertThat(result.variables())
                .containsEntry(PlannerNode.ROUTE_KEY, PlannerNode.CHAT_ROUTE)
                .containsEntry(PlannerNode.FINAL_RESPONSE_KEY, "围栏回答")
                .doesNotContainKey(PlannerNode.ERROR_KEY);
    }

    @Test
    void normalizesJsonRouteOutput() {
        Endpoint endpoint = endpoint();
        endpoint.server().expect(once(), requestTo(endpoint.baseUrl() + PATH))
                .andRespond(withSuccess("""
                        {"id":"route-response","object":"chat.completion","created":1,
                         "model":"planner-model","choices":[{"index":0,
                         "message":{"role":"assistant","content":"{\\"route\\":\\"chat\\"}"},
                         "finish_reason":"stop"}]}
                        """, MediaType.APPLICATION_JSON));
        endpoint.server().expect(once(), requestTo(endpoint.baseUrl() + PATH))
                .andRespond(withSuccess("""
                        {"id":"answer-response","object":"chat.completion","created":1,
                         "model":"planner-model","choices":[{"index":0,
                         "message":{"role":"assistant","content":"JSON 路由回答"},
                         "finish_reason":"stop"}]}
                        """, MediaType.APPLICATION_JSON));

        PlannerNode node = new PlannerNode(
                router(endpoint), request -> {
                    throw new AssertionError("聊天语义路由不应召回代码仓库记忆");
                }, 7);

        AgentState result = node.execute(AgentState.empty()
                .withVariable(PlannerNode.TASK_KEY, "按天气规划"));

        assertThat(result.variables())
                .containsEntry(PlannerNode.ROUTE_KEY, PlannerNode.CHAT_ROUTE)
                .containsEntry(PlannerNode.FINAL_RESPONSE_KEY, "JSON 路由回答")
                .doesNotContainKey(PlannerNode.ERROR_KEY);
    }

    @Test
    void downgradesSemanticAgentForNaturalLanguageTask() {
        Endpoint endpoint = endpoint();
        endpoint.server().expect(once(), requestTo(endpoint.baseUrl() + PATH))
                .andRespond(withSuccess("""
                        {"id":"route-response","object":"chat.completion","created":1,
                         "model":"planner-model","choices":[{"index":0,
                         "message":{"role":"assistant","content":"agent"},
                         "finish_reason":"stop"}]}
                        """, MediaType.APPLICATION_JSON));
        endpoint.server().expect(once(), requestTo(endpoint.baseUrl() + PATH))
                .andRespond(withSuccess("""
                        {"id":"answer-response","object":"chat.completion","created":1,
                         "model":"planner-model","choices":[{"index":0,
                         "message":{"role":"assistant","content":"天气规划回答"},
                         "finish_reason":"stop"}]}
                        """, MediaType.APPLICATION_JSON));

        PlannerNode node = new PlannerNode(
                router(endpoint), request -> {
                    throw new AssertionError("自然语言任务不应召回代码仓库记忆");
                }, 7);

        AgentState result = node.execute(AgentState.empty()
                .withVariable(PlannerNode.TASK_KEY, "按天气规划"));

        assertThat(result.variables())
                .containsEntry(PlannerNode.ROUTE_KEY, PlannerNode.CHAT_ROUTE)
                .containsEntry(PlannerNode.FINAL_RESPONSE_KEY, "天气规划回答")
                .doesNotContainKey(PlannerNode.ERROR_KEY);
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
                .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString())
                .withVariable(PlannerNode.TASK_KEY, "修复代码"));

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
                .contains("planner.task")
                .contains("IllegalArgumentException");
        assertThat(result.trace()).containsExactly("planner");
    }

    @Test
    void recordsTypedIntentPromptAndContextEvidenceForChat() {
        Endpoint endpoint = endpoint();
        endpoint.server().expect(once(), requestTo(endpoint.baseUrl() + PATH))
                .andRespond(withSuccess("""
                        {"id":"answer-response","object":"chat.completion","created":1,
                         "model":"planner-model","choices":[{"index":0,
                         "message":{"role":"assistant","content":"我是一个 AI 助手。"},
                         "finish_reason":"stop"}]}
                        """, MediaType.APPLICATION_JSON));

        PlannerNode node = new PlannerNode(
                router(endpoint), request -> new MemoryContext("", 0), 7);

        AgentState result = node.execute(AgentState.empty()
                .withVariable(PlannerNode.TASK_KEY, "你是什么模型"));

        assertThat(result.variables())
                .containsEntry("planner.taskKind", "CHAT")
                .containsEntry("planner.complexity", "SIMPLE")
                .containsEntry("planner.requiredCapabilities", "")
                .containsKey("planner.routeReason")
                .containsEntry("planner.responsePromptName", "planner.chat")
                .containsEntry("planner.responsePromptVersion", "1")
                .containsKey("planner.responsePromptFingerprint")
                .containsKey("planner.contextEstimatedTokens")
                .containsKey("planner.contextDroppedMessages")
                .containsEntry("planner.contextSummarized", "false");
    }

    @Test
    void answersProjectKnowledgeQuestionAndStoresAuditableEvidence() throws Exception {
        Endpoint endpoint = endpoint();
        endpoint.server().expect(once(), requestTo(endpoint.baseUrl() + PATH))
                .andExpect(content().json("""
                        {
                          "model":"planner-model",
                          "messages":[
                            {"role":"system"},
                            {"role":"user","content":"任务:\n请解释当前仓库架构\n\n项目知识上下文:\n[PROJECT_FILE] AGENTS.md"}
                          ],
                          "tools":[],"temperature":0.0,"stream":false
                        }
                        """, false))
                .andRespond(withSuccess("""
                        {"id":"knowledge-response","object":"chat.completion","created":1,
                         "model":"planner-model","choices":[{"index":0,
                         "message":{"role":"assistant","content":"当前仓库由核心、沙箱和 Web 模块组成。"},
                         "finish_reason":"stop"}]}
                        """, MediaType.APPLICATION_JSON));
        KnowledgeContext context = knowledgeContext();
        RecordingKnowledgeProvider knowledgeProvider =
                new RecordingKnowledgeProvider(context);
        PlannerNode node = fullNode(
                router(endpoint),
                request -> {
                    throw new AssertionError("项目知识问答不应召回长期记忆");
                },
                knowledgeProvider,
                (history, task) -> knowledgeDecision());

        AgentState result = node.execute(AgentState.empty()
                .withVariable(PlannerNode.REPOSITORY_ID_KEY, "repo")
                .withVariable(PlannerNode.USER_ID_KEY, "user")
                .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString())
                .withVariable(PlannerNode.TASK_KEY, "请解释当前仓库架构"));

        Path normalizedWorkspace = workspace.toAbsolutePath().normalize();
        assertThat(knowledgeProvider.request).isEqualTo(new KnowledgeContextRequest(
                "repo",
                "user",
                normalizedWorkspace,
                normalizedWorkspace,
                "请解释当前仓库架构",
                TaskComplexity.STANDARD,
                4000));
        assertThat(result.variables())
                .containsEntry(PlannerNode.ROUTE_KEY, PlannerNode.KNOWLEDGE_ROUTE)
                .containsEntry(PlannerNode.FINAL_RESPONSE_KEY,
                        "当前仓库由核心、沙箱和 Web 模块组成。")
                .containsEntry(PlannerNode.KNOWLEDGE_CONTEXT_KEY, context.prompt())
                .containsEntry(PlannerNode.KNOWLEDGE_FINGERPRINT_KEY, context.fingerprint())
                .containsEntry(PlannerNode.KNOWLEDGE_SOURCES_KEY, "1")
                .containsEntry(PlannerNode.KNOWLEDGE_DEGRADED_KEY, "false")
                .containsEntry(PlannerNode.RESPONSE_PROMPT_NAME_KEY, "planner.knowledge")
                .containsEntry(PlannerNode.RESPONSE_PROMPT_VERSION_KEY, "1")
                .doesNotContainKeys(PlannerNode.PLAN_KEY, PlannerNode.ERROR_KEY);
        assertThat(objectMapper.readTree(
                result.variables().get(PlannerNode.KNOWLEDGE_EVIDENCE_KEY)))
                .isEqualTo(objectMapper.valueToTree(context.evidence()));
        assertThat(result.trace()).containsExactly("planner");
    }

    @Test
    void injectsMemoryAndKnowledgeIntoVersionTwoCodePlan() {
        Endpoint endpoint = endpoint();
        endpoint.server().expect(once(), requestTo(endpoint.baseUrl() + PATH))
                .andExpect(content().json("""
                        {
                          "model":"planner-model",
                          "messages":[
                            {"role":"system"},
                            {"role":"user","content":"任务:\n修改 PlannerNode\n\n长期记忆上下文:\n保持强类型\n\n项目知识上下文:\n[PROJECT_FILE] AGENTS.md"}
                          ],
                          "tools":[],"temperature":0.0,"stream":false
                        }
                        """, false))
                .andRespond(withSuccess("""
                        {"id":"plan-response","object":"chat.completion","created":1,
                         "model":"planner-model","choices":[{"index":0,
                         "message":{"role":"assistant","content":"先补测试，再修改 PlannerNode。"},
                         "finish_reason":"stop"}]}
                        """, MediaType.APPLICATION_JSON));
        RecordingProvider memoryProvider = new RecordingProvider(
                new MemoryContext("保持强类型", 1));
        RecordingKnowledgeProvider knowledgeProvider =
                new RecordingKnowledgeProvider(knowledgeContext());
        PlannerNode node = fullNode(
                router(endpoint),
                memoryProvider,
                knowledgeProvider,
                (history, task) -> agentDecision());

        AgentState result = node.execute(AgentState.empty()
                .withVariable(PlannerNode.REPOSITORY_ID_KEY, "repo")
                .withVariable(PlannerNode.USER_ID_KEY, "user")
                .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString())
                .withVariable(PlannerNode.TASK_KEY, "修改 PlannerNode"));

        assertThat(memoryProvider.request).isEqualTo(
                new MemoryContextRequest("repo", "user", "修改 PlannerNode", 5));
        assertThat(knowledgeProvider.request.complexity()).isEqualTo(TaskComplexity.STANDARD);
        assertThat(result.variables())
                .containsEntry(PlannerNode.PLAN_KEY, "先补测试，再修改 PlannerNode。")
                .containsEntry(PlannerNode.KNOWLEDGE_CONTEXT_KEY,
                        "[PROJECT_FILE] AGENTS.md")
                .containsEntry(PlannerNode.RESPONSE_PROMPT_NAME_KEY, "planner.plan")
                .containsEntry(PlannerNode.RESPONSE_PROMPT_VERSION_KEY, "2")
                .containsEntry(PlannerNode.ROUTE_KEY, PlannerNode.AGENT_ROUTE)
                .doesNotContainKey(PlannerNode.ERROR_KEY);
    }

    @Test
    void preservesKnowledgeFailureStackAndStopsBeforeCoderPlan() {
        PlannerNode node = fullNode(
                routerWithoutRequests(),
                request -> new MemoryContext("memory", 1),
                request -> {
                    throw new IllegalStateException("knowledge unavailable");
                },
                (history, task) -> agentDecision());

        AgentState result = node.execute(AgentState.empty()
                .withVariable(PlannerNode.REPOSITORY_ID_KEY, "repo")
                .withVariable(PlannerNode.USER_ID_KEY, "user")
                .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString())
                .withVariable(PlannerNode.TASK_KEY, "修改 PlannerNode"));

        assertThat(result.variables())
                .containsEntry(PlannerNode.ROUTE_KEY, PlannerNode.FAILED_ROUTE)
                .containsKey(PlannerNode.ERROR_KEY)
                .doesNotContainKey(PlannerNode.PLAN_KEY);
        assertThat(result.variables().get(PlannerNode.ERROR_KEY))
                .contains("IllegalStateException")
                .contains("knowledge unavailable");
    }

    @Test
    void preparesToolOperationWithoutLoadingProjectKnowledge() {
        PlannerNode node = fullNode(
                routerWithoutRequests(),
                request -> {
                    throw new AssertionError("工具任务不应召回记忆");
                },
                request -> {
                    throw new AssertionError("工具任务不应加载项目知识");
                },
                (history, task) -> new TaskDecision(
                        TaskRoute.AGENT,
                        TaskKind.TOOL_OPERATION,
                        TaskComplexity.STANDARD,
                        Set.of(RequiredCapability.TOOL),
                        "检测到工具动作"));

        AgentState result = node.execute(AgentState.empty()
                .withVariable(PlannerNode.REPOSITORY_ID_KEY, "repo")
                .withVariable(PlannerNode.USER_ID_KEY, "user")
                .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString())
                .withVariable(PlannerNode.TASK_KEY, "生成图片"));

        assertThat(result.variables())
                .containsEntry(PlannerNode.ROUTE_KEY, PlannerNode.AGENT_ROUTE)
                .containsEntry(PlannerNode.PLAN_KEY,
                        "识别为工具任务，将调用已注册并受治理的工具完成请求。")
                .doesNotContainKey(PlannerNode.ERROR_KEY);
        assertThat(result.trace()).containsExactly("planner");
    }

    @Test
    void keepsHistoricalPlanPromptAndAddsVersionedKnowledgePrompts() {
        var catalog = PlannerPromptTemplates.catalog();

        assertThat(catalog.render("planner.plan", "1", Map.of(
                "task", "task",
                "memory", "memory")).dynamicSection())
                .isEqualTo("任务:\ntask\n\n长期记忆上下文:\nmemory");
        assertThat(catalog.render("planner.plan", "2", Map.of(
                "task", "task",
                "memory", "memory",
                "knowledge", "knowledge")).dynamicSection())
                .isEqualTo("任务:\ntask\n\n长期记忆上下文:\nmemory"
                        + "\n\n项目知识上下文:\nknowledge");
        var knowledgePrompt = catalog.render("planner.knowledge", "1", Map.of(
                "task", "task",
                "knowledge", "knowledge"));
        assertThat(knowledgePrompt.staticSection())
                .contains("按证据回答")
                .contains("证据不足")
                .contains("禁止声称执行了写入或终端命令");
    }

    private PlannerNode fullNode(
            ModelRouter router,
            MemoryContextProvider memoryProvider,
            KnowledgeContextProvider knowledgeProvider,
            com.agent.core.intent.IntentClassifier classifier) {
        return new PlannerNode(
                router,
                memoryProvider,
                5,
                knowledgeProvider,
                4000,
                objectMapper,
                PlannerPromptTemplates.catalog(),
                PlannerNode.defaultContextWindowManager(),
                classifier,
                12_000);
    }

    private TaskDecision knowledgeDecision() {
        return new TaskDecision(
                TaskRoute.KNOWLEDGE,
                TaskKind.PROJECT_QUERY,
                TaskComplexity.STANDARD,
                Set.of(RequiredCapability.CODE_READ),
                "读取项目知识");
    }

    private TaskDecision agentDecision() {
        return new TaskDecision(
                TaskRoute.AGENT,
                TaskKind.CODE_CHANGE,
                TaskComplexity.STANDARD,
                Set.of(RequiredCapability.CODE_READ, RequiredCapability.CODE_WRITE),
                "修改代码");
    }

    private KnowledgeContext knowledgeContext() {
        return new KnowledgeContext(
                "[PROJECT_FILE] AGENTS.md",
                1,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                32,
                false,
                List.of(new KnowledgeEvidence(
                        KnowledgeEvidenceKind.PROJECT_FILE,
                        "AGENTS.md",
                        KnowledgeEvidenceStatus.APPLIED,
                        "loaded",
                        null)));
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

    private static final class RecordingKnowledgeProvider
            implements KnowledgeContextProvider {
        private final KnowledgeContext response;
        private KnowledgeContextRequest request;

        private RecordingKnowledgeProvider(KnowledgeContext response) {
            this.response = response;
        }

        @Override
        public KnowledgeContext load(KnowledgeContextRequest request) {
            this.request = request;
            return response;
        }
    }
}
