package com.agent.core.nodes;

import com.agent.core.context.ContextWindow;
import com.agent.core.context.ContextWindowManager;
import com.agent.core.context.ContextWindowRequest;
import com.agent.core.context.Utf8TokenEstimator;
import com.agent.core.engine.AgentState;
import com.agent.core.engine.Node;
import com.agent.core.engine.NodeExecutionContext;
import com.agent.core.intent.IntentClassifier;
import com.agent.core.intent.ModelIntentClassifier;
import com.agent.core.intent.ModelRouterIntentModel;
import com.agent.core.intent.RequiredCapability;
import com.agent.core.intent.TaskDecision;
import com.agent.core.intent.TaskKind;
import com.agent.core.intent.TaskRoute;
import com.agent.core.knowledge.KnowledgeContext;
import com.agent.core.knowledge.KnowledgeContextProvider;
import com.agent.core.knowledge.KnowledgeContextRequest;
import com.agent.core.llm.ChatMessage;
import com.agent.core.llm.ModelRequest;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.RoutedCompletion;
import com.agent.core.llm.TaskType;
import com.agent.core.memory.MemoryContext;
import com.agent.core.memory.MemoryContextProvider;
import com.agent.core.memory.MemoryContextRequest;
import com.agent.core.prompt.PromptCatalog;
import com.agent.core.prompt.RenderedPrompt;
import com.agent.core.security.DefaultPromptInjectionDetector;
import com.agent.core.security.PromptInjectionDetector;
import com.agent.core.security.PromptSecurityAssessment;
import com.agent.core.security.PromptSecurityContext;
import com.agent.core.security.SecurityDecision;
import com.agent.core.security.SecurityFinding;
import com.agent.core.security.SecurityViolation;
import com.agent.core.security.SecurityViolationSink;
import com.agent.core.security.SecurityViolationType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** 在 Prompt、上下文窗口和强类型意图决策约束下生成任务计划或最终回答。 */
public final class PlannerNode implements Node {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlannerNode.class);
    private static final int DEFAULT_MAX_CONTEXT_TOKENS = 12_000;
    private static final int DEFAULT_KNOWLEDGE_MAX_TOKENS = 4_000;
    private static final int SUMMARY_MAX_TOKENS = 800;

    public static final String REPOSITORY_ID_KEY = "planner.repositoryId";
    public static final String USER_ID_KEY = "planner.userId";
    public static final String TASK_KEY = "planner.task";
    public static final String MEMORY_CONTEXT_KEY = "planner.memoryContext";
    public static final String KNOWLEDGE_CONTEXT_KEY = "planner.knowledgeContext";
    public static final String KNOWLEDGE_FINGERPRINT_KEY = "planner.knowledgeFingerprint";
    public static final String KNOWLEDGE_SOURCES_KEY = "planner.knowledgeSources";
    public static final String KNOWLEDGE_EVIDENCE_KEY = "planner.knowledgeEvidence";
    public static final String KNOWLEDGE_DEGRADED_KEY = "planner.knowledgeDegraded";
    public static final String PLAN_KEY = "planner.plan";
    public static final String MODEL_KEY = "planner.model";
    public static final String REQUEST_KEY = "planner.request";
    public static final String RESPONSE_KEY = "planner.response";
    public static final String ROUTE_KEY = "planner.route";
    public static final String ERROR_KEY = "planner.error";
    public static final String FINAL_RESPONSE_KEY = "final_response";
    public static final String TASK_KIND_KEY = "planner.taskKind";
    public static final String COMPLEXITY_KEY = "planner.complexity";
    public static final String REQUIRED_CAPABILITIES_KEY = "planner.requiredCapabilities";
    public static final String ROUTE_REASON_KEY = "planner.routeReason";
    public static final String ROUTE_PROMPT_FINGERPRINT_KEY = "planner.routePromptFingerprint";
    public static final String RESPONSE_PROMPT_NAME_KEY = "planner.responsePromptName";
    public static final String RESPONSE_PROMPT_VERSION_KEY = "planner.responsePromptVersion";
    public static final String RESPONSE_PROMPT_FINGERPRINT_KEY = "planner.responsePromptFingerprint";
    public static final String CONTEXT_ESTIMATED_TOKENS_KEY = "planner.contextEstimatedTokens";
    public static final String CONTEXT_DROPPED_MESSAGES_KEY = "planner.contextDroppedMessages";
    public static final String CONTEXT_SUMMARIZED_KEY = "planner.contextSummarized";

    /** 当前 Run 关联的会话标识。 */
    public static final String CONVERSATION_ID_KEY = "conversation.id";
    /** 当前 Run 关联的会话轮次标识。 */
    public static final String CONVERSATION_TURN_ID_KEY = "conversation.turnId";

    public static final String CHAT_ROUTE = "chat";
    public static final String KNOWLEDGE_ROUTE = "knowledge";
    public static final String AGENT_ROUTE = "agent";
    public static final String FAILED_ROUTE = "failed";

    private final ModelRouter modelRouter;
    private final MemoryContextProvider memoryContextProvider;
    private final int memoryLimit;
    private final KnowledgeContextProvider knowledgeContextProvider;
    private final int knowledgeMaxTokens;
    private final ObjectMapper objectMapper;
    private final PromptCatalog promptCatalog;
    private final ContextWindowManager contextWindowManager;
    private final IntentClassifier intentClassifier;
    private final int maxContextTokens;
    private final PromptInjectionDetector promptInjectionDetector;
    private final SecurityViolationSink securityViolationSink;

    /** 创建使用默认 Prompt、上下文和分类策略的 Planner。 */
    public PlannerNode(
            ModelRouter modelRouter,
            MemoryContextProvider memoryContextProvider,
            int memoryLimit) {
        this(
                modelRouter,
                memoryContextProvider,
                memoryLimit,
                KnowledgeContextProvider.empty(),
                DEFAULT_KNOWLEDGE_MAX_TOKENS,
                new ObjectMapper(),
                PlannerPromptTemplates.catalog(),
                defaultContextWindowManager(),
                null,
                DEFAULT_MAX_CONTEXT_TOKENS,
                new DefaultPromptInjectionDetector(),
                SecurityViolationSink.noop());
    }

    /** 返回生产装配共用的确定性上下文窗口策略。 */
    public static ContextWindowManager defaultContextWindowManager() {
        return new ContextWindowManager(
                new Utf8TokenEstimator(), PlannerNode::summarizeHistory);
    }

    /** 创建全部策略均由构造器注入的 Planner。 */
    public PlannerNode(
            ModelRouter modelRouter,
            MemoryContextProvider memoryContextProvider,
            int memoryLimit,
            PromptCatalog promptCatalog,
            ContextWindowManager contextWindowManager,
            IntentClassifier intentClassifier,
            int maxContextTokens) {
        this(
                modelRouter,
                memoryContextProvider,
                memoryLimit,
                KnowledgeContextProvider.empty(),
                DEFAULT_KNOWLEDGE_MAX_TOKENS,
                new ObjectMapper(),
                promptCatalog,
                contextWindowManager,
                intentClassifier,
                maxContextTokens,
                new DefaultPromptInjectionDetector(),
                SecurityViolationSink.noop());
    }

    /** 创建包含长期记忆与项目知识策略的完整 Planner。 */
    public PlannerNode(
            ModelRouter modelRouter,
            MemoryContextProvider memoryContextProvider,
            int memoryLimit,
            KnowledgeContextProvider knowledgeContextProvider,
            int knowledgeMaxTokens,
            ObjectMapper objectMapper,
            PromptCatalog promptCatalog,
            ContextWindowManager contextWindowManager,
            IntentClassifier intentClassifier,
            int maxContextTokens) {
        this(
                modelRouter,
                memoryContextProvider,
                memoryLimit,
                knowledgeContextProvider,
                knowledgeMaxTokens,
                objectMapper,
                promptCatalog,
                contextWindowManager,
                intentClassifier,
                maxContextTokens,
                new DefaultPromptInjectionDetector(),
                SecurityViolationSink.noop());
    }

    /** 创建包含 Prompt Injection 检查与违规持久化策略的 Planner。 */
    public PlannerNode(
            ModelRouter modelRouter,
            MemoryContextProvider memoryContextProvider,
            int memoryLimit,
            KnowledgeContextProvider knowledgeContextProvider,
            int knowledgeMaxTokens,
            ObjectMapper objectMapper,
            PromptCatalog promptCatalog,
            ContextWindowManager contextWindowManager,
            IntentClassifier intentClassifier,
            int maxContextTokens,
            PromptInjectionDetector promptInjectionDetector,
            SecurityViolationSink securityViolationSink) {
        this.modelRouter = Objects.requireNonNull(modelRouter, "modelRouter 不能为空");
        this.memoryContextProvider = Objects.requireNonNull(
                memoryContextProvider, "memoryContextProvider 不能为空");
        if (memoryLimit < 1 || memoryLimit > 20) {
            throw new IllegalArgumentException("memoryLimit 必须在 1 到 20 之间");
        }
        this.memoryLimit = memoryLimit;
        this.knowledgeContextProvider = Objects.requireNonNull(
                knowledgeContextProvider, "knowledgeContextProvider 不能为空");
        if (knowledgeMaxTokens < 1) {
            throw new IllegalArgumentException("knowledgeMaxTokens 必须大于 0");
        }
        this.knowledgeMaxTokens = knowledgeMaxTokens;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.promptCatalog = Objects.requireNonNull(promptCatalog, "promptCatalog 不能为空");
        this.contextWindowManager = Objects.requireNonNull(
                contextWindowManager, "contextWindowManager 不能为空");
        this.intentClassifier = intentClassifier == null
                ? new ModelIntentClassifier(
                        new ModelRouterIntentModel(modelRouter),
                        objectMapper,
                        promptCatalog)
                : intentClassifier;
        if (maxContextTokens < 1) {
            throw new IllegalArgumentException("maxContextTokens 必须大于 0");
        }
        this.maxContextTokens = maxContextTokens;
        this.promptInjectionDetector = Objects.requireNonNull(
                promptInjectionDetector, "promptInjectionDetector 不能为空");
        this.securityViolationSink = Objects.requireNonNull(
                securityViolationSink, "securityViolationSink 不能为空");
    }

    /** 执行任务决策、记忆召回和最终模型调用。 */
    @Override
    public AgentState execute(AgentState state) {
        Objects.requireNonNull(state, "state 不能为空");
        AgentState output = state;
        try {
            NodeExecutionContext.progress("正在识别任务意图");
            String task = requireVariable(state, TASK_KEY);
            if (!checkPrompt(state, "user.task", task)) {
                return failedSecurityState(state, "user.task");
            }
            ChatMessage toolError = latestToolError(state);
            if (toolError != null
                    && toolError.content() instanceof ChatMessage.TextContent textContent
                    && !checkPrompt(state, "tool.output", textContent.text())) {
                return failedSecurityState(state, "tool.output");
            }
            RenderedPrompt routePrompt = promptCatalog.render(
                    "planner.route", "1", Map.of("task", task));
            TaskDecision decision = intentClassifier.classify(state.messages(), task);
            output = withDecisionEvidence(state, decision)
                    .withVariable(ROUTE_PROMPT_FINGERPRINT_KEY, routePrompt.fingerprint());
            NodeExecutionContext.progress(
                    "任务意图已确定: " + decision.taskKind().name());
            if (decision.route() == TaskRoute.CHAT) {
                return answerChat(output, task);
            }

            String repositoryId = requireVariable(output, REPOSITORY_ID_KEY);
            String userId = requireVariable(output, USER_ID_KEY);
            Path workspace = Path.of(requireVariable(output, CoderNode.WORKSPACE_PATH_KEY))
                    .toAbsolutePath()
                    .normalize();
            if (decision.route() == TaskRoute.KNOWLEDGE) {
                KnowledgeContext knowledgeContext = loadKnowledge(
                        repositoryId, userId, workspace, task, decision);
                if (!checkPrompt(output, "project.knowledge", knowledgeContext.prompt())) {
                    return failedSecurityState(output, "project.knowledge");
                }
                output = withKnowledgeEvidence(output, knowledgeContext);
                return answerKnowledge(output, task, knowledgeContext);
            }

            NodeExecutionContext.progress("正在检索任务相关记忆");
            MemoryContext memoryContext = Objects.requireNonNull(
                    memoryContextProvider.recall(new MemoryContextRequest(
                            repositoryId, userId, task, memoryLimit)),
                    "记忆上下文不能为空");
            KnowledgeContext knowledgeContext = loadKnowledge(
                    repositoryId, userId, workspace, task, decision);
            if (!checkPrompt(output, "project.knowledge", knowledgeContext.prompt())) {
                return failedSecurityState(output, "project.knowledge");
            }
            output = withKnowledgeEvidence(output, knowledgeContext);
            RenderedPrompt planPrompt = promptCatalog.render(
                    "planner.plan", "2", Map.of(
                            "task", task,
                            "memory", memoryContext.prompt(),
                            "knowledge", knowledgeContext.prompt()));
            ContextWindow contextWindow = contextWindowManager.fit(
                    new ContextWindowRequest(
                            ChatMessage.system(planPrompt.staticSection()),
                            output.messages(),
                            ChatMessage.user(planPrompt.dynamicSection()),
                            latestToolError(output),
                            maxContextTokens,
                            Math.min(SUMMARY_MAX_TOKENS, maxContextTokens)));
            String requestText = planPrompt.dynamicSection();
            output = output
                    .withVariable(MEMORY_CONTEXT_KEY, memoryContext.prompt())
                    .withVariable(REQUEST_KEY, requestText)
                    .withVariable(CONTEXT_ESTIMATED_TOKENS_KEY,
                            Integer.toString(contextWindow.estimatedTokens()))
                    .withVariable(CONTEXT_DROPPED_MESSAGES_KEY,
                            Integer.toString(contextWindow.droppedMessages()))
                    .withVariable(CONTEXT_SUMMARIZED_KEY,
                            Boolean.toString(contextWindow.summarized()));
            RoutedCompletion completion = modelRouter.complete(
                    TaskType.CODE,
                    new ModelRequest(contextWindow.messages(), List.of(), null, 0.0));
            NodeExecutionContext.progress("规划模型已返回执行计划");
            ChatMessage message = completion.response().choices().getFirst().message();
            if (!(message.content() instanceof ChatMessage.TextContent textContent)) {
                throw new IllegalStateException("规划模型响应 content 必须是 TextContent");
            }
            return output
                    .withMessage(ChatMessage.user(task))
                    .withMessage(ChatMessage.assistant(textContent.text()))
                    .withVariable(PLAN_KEY, textContent.text())
                    .withVariable(RESPONSE_KEY, textContent.text())
                    .withVariable(MODEL_KEY, completion.model())
                    .withVariable(RESPONSE_PROMPT_NAME_KEY, planPrompt.name())
                    .withVariable(RESPONSE_PROMPT_VERSION_KEY, planPrompt.version())
                    .withVariable(RESPONSE_PROMPT_FINGERPRINT_KEY, planPrompt.fingerprint())
                    .withVariable(ROUTE_KEY, AGENT_ROUTE)
                    .withTraceEntry("planner");
        } catch (Exception exception) {
            LOGGER.error(
                    "Planner 节点执行失败 task={} route={} error={}",
                    safeSummary(state.variables().get(TASK_KEY)),
                    output.variables().get(ROUTE_KEY),
                    exception.getMessage(),
                    exception);
            return output
                    .withVariable(ERROR_KEY, stackTrace(exception))
                    .withVariable(ROUTE_KEY, FAILED_ROUTE)
                    .withTraceEntry("planner");
        }
    }

    private AgentState answerChat(AgentState state, String task) {
        NodeExecutionContext.progress("已识别为快速问答，跳过代码工具链");
        RenderedPrompt prompt = promptCatalog.render(
                "planner.chat", "1", Map.of("task", task));
        ContextWindow contextWindow = contextWindowManager.fit(
                new ContextWindowRequest(
                        ChatMessage.system(prompt.staticSection()),
                        state.messages(),
                        ChatMessage.user(prompt.dynamicSection()),
                        latestToolError(state),
                        maxContextTokens,
                        Math.min(SUMMARY_MAX_TOKENS, maxContextTokens)));
        ModelRequest request = new ModelRequest(
                contextWindow.messages(), List.of(), null, 0.0);
        RoutedCompletion completion = modelRouter.complete(
                TaskType.QUICK_CLASSIFICATION, request);
        ChatMessage message = completion.response().choices().getFirst().message();
        if (!(message.content() instanceof ChatMessage.TextContent textContent)) {
            throw new IllegalStateException("快速问答模型响应 content 必须是 TextContent");
        }
        String response = textContent.text();
        if (response.isBlank()) {
            throw new IllegalStateException("快速问答模型响应不能为空");
        }
        NodeExecutionContext.progress("快速问答已生成最终回答");
        return state
                .withMessage(ChatMessage.user(task))
                .withMessage(ChatMessage.assistant(response))
                .withVariable(RESPONSE_KEY, response)
                .withVariable(FINAL_RESPONSE_KEY, response)
                .withVariable(MODEL_KEY, completion.model())
                .withVariable(RESPONSE_PROMPT_NAME_KEY, prompt.name())
                .withVariable(RESPONSE_PROMPT_VERSION_KEY, prompt.version())
                .withVariable(RESPONSE_PROMPT_FINGERPRINT_KEY, prompt.fingerprint())
                .withVariable(CONTEXT_ESTIMATED_TOKENS_KEY,
                        Integer.toString(contextWindow.estimatedTokens()))
                .withVariable(CONTEXT_DROPPED_MESSAGES_KEY,
                        Integer.toString(contextWindow.droppedMessages()))
                .withVariable(CONTEXT_SUMMARIZED_KEY,
                        Boolean.toString(contextWindow.summarized()))
                .withVariable(ROUTE_KEY, CHAT_ROUTE)
                .withTraceEntry("planner");
    }

    private AgentState answerKnowledge(
            AgentState state,
            String task,
            KnowledgeContext knowledgeContext) {
        NodeExecutionContext.progress("项目知识已加载，正在生成证据化回答");
        RenderedPrompt prompt = promptCatalog.render(
                "planner.knowledge", "1", Map.of(
                        "task", task,
                        "knowledge", knowledgeContext.prompt()));
        ContextWindow contextWindow = contextWindowManager.fit(
                new ContextWindowRequest(
                        ChatMessage.system(prompt.staticSection()),
                        state.messages(),
                        ChatMessage.user(prompt.dynamicSection()),
                        latestToolError(state),
                        maxContextTokens,
                        Math.min(SUMMARY_MAX_TOKENS, maxContextTokens)));
        RoutedCompletion completion = modelRouter.complete(
                TaskType.CODE,
                new ModelRequest(contextWindow.messages(), List.of(), null, 0.0));
        ChatMessage message = completion.response().choices().getFirst().message();
        if (!(message.content() instanceof ChatMessage.TextContent textContent)) {
            throw new IllegalStateException("项目知识模型响应 content 必须是 TextContent");
        }
        String response = textContent.text();
        if (response.isBlank()) {
            throw new IllegalStateException("项目知识模型响应不能为空");
        }
        NodeExecutionContext.progress("项目知识回答已生成");
        return state
                .withMessage(ChatMessage.user(task))
                .withMessage(ChatMessage.assistant(response))
                .withVariable(REQUEST_KEY, prompt.dynamicSection())
                .withVariable(RESPONSE_KEY, response)
                .withVariable(FINAL_RESPONSE_KEY, response)
                .withVariable(MODEL_KEY, completion.model())
                .withVariable(RESPONSE_PROMPT_NAME_KEY, prompt.name())
                .withVariable(RESPONSE_PROMPT_VERSION_KEY, prompt.version())
                .withVariable(RESPONSE_PROMPT_FINGERPRINT_KEY, prompt.fingerprint())
                .withVariable(CONTEXT_ESTIMATED_TOKENS_KEY,
                        Integer.toString(contextWindow.estimatedTokens()))
                .withVariable(CONTEXT_DROPPED_MESSAGES_KEY,
                        Integer.toString(contextWindow.droppedMessages()))
                .withVariable(CONTEXT_SUMMARIZED_KEY,
                        Boolean.toString(contextWindow.summarized()))
                .withVariable(ROUTE_KEY, KNOWLEDGE_ROUTE)
                .withTraceEntry("planner");
    }

    private KnowledgeContext loadKnowledge(
            String repositoryId,
            String userId,
            Path workspace,
            String task,
            TaskDecision decision) {
        NodeExecutionContext.progress("正在加载当前项目知识");
        return Objects.requireNonNull(
                knowledgeContextProvider.load(new KnowledgeContextRequest(
                        repositoryId,
                        userId,
                        workspace,
                        workspace,
                        task,
                        decision.complexity(),
                        knowledgeMaxTokens)),
                "知识上下文不能为空");
    }

    private AgentState withKnowledgeEvidence(
            AgentState state,
            KnowledgeContext knowledgeContext) throws JsonProcessingException {
        String evidence = objectMapper.writeValueAsString(knowledgeContext.evidence());
        return state
                .withVariable(KNOWLEDGE_CONTEXT_KEY, knowledgeContext.prompt())
                .withVariable(KNOWLEDGE_FINGERPRINT_KEY, knowledgeContext.fingerprint())
                .withVariable(KNOWLEDGE_SOURCES_KEY,
                        Integer.toString(knowledgeContext.sourceCount()))
                .withVariable(KNOWLEDGE_EVIDENCE_KEY, evidence)
                .withVariable(KNOWLEDGE_DEGRADED_KEY,
                        Boolean.toString(knowledgeContext.degraded()));
    }

    private AgentState withDecisionEvidence(AgentState state, TaskDecision decision) {
        String capabilities = decision.requiredCapabilities().stream()
                .sorted(Comparator.comparing(Enum::name))
                .map(RequiredCapability::name)
                .collect(Collectors.joining(","));
        return state
                .withVariable(TASK_KIND_KEY, decision.taskKind().name())
                .withVariable(COMPLEXITY_KEY, decision.complexity().name())
                .withVariable(REQUIRED_CAPABILITIES_KEY, capabilities)
                .withVariable(ROUTE_REASON_KEY, decision.reason())
                .withVariable(ROUTE_KEY, switch (decision.route()) {
                    case CHAT -> CHAT_ROUTE;
                    case KNOWLEDGE -> KNOWLEDGE_ROUTE;
                    case AGENT -> AGENT_ROUTE;
                });
    }

    private ChatMessage latestToolError(AgentState state) {
        for (String key : List.of("coder.error", "ops.error", "reviewer.error")) {
            String value = state.variables().get(key);
            if (value != null && !value.isBlank()) {
                return ChatMessage.tool("planner-error", value);
            }
        }
        return null;
    }

    private static String summarizeHistory(List<ChatMessage> messages, int maxTokens) {
        int maxCharacters = Math.max(1, maxTokens * 4);
        StringBuilder summary = new StringBuilder();
        for (ChatMessage message : messages) {
            String content = message.content() instanceof ChatMessage.TextContent text
                    ? text.text() : "[非文本消息]";
            String line = message.role().jsonValue() + ": " + content + "\n";
            if (summary.length() + line.length() > maxCharacters) {
                break;
            }
            summary.append(line);
        }
        return summary.toString().strip();
    }

    private String requireVariable(AgentState state, String key) {
        String value = state.variables().get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少状态变量: " + key);
        }
        return value;
    }

    private String safeSummary(String value) {
        if (value == null) {
            return "";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= 160 ? compact : compact.substring(0, 160) + "…";
    }

    private String stackTrace(Exception exception) {
        StringWriter writer = new StringWriter();
        exception.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    /** 执行单一来源的安全检查，并只发布规则摘要。 */
    private boolean checkPrompt(AgentState state, String source, String text) {
        UUID runId = currentRunId();
        PromptSecurityAssessment assessment = promptInjectionDetector.inspect(
                new PromptSecurityContext(
                        runId,
                        state.variables().getOrDefault(USER_ID_KEY, "unknown"),
                        "planner",
                        source),
                text);
        for (SecurityFinding finding : assessment.findings()) {
            if (assessment.decision() == SecurityDecision.FLAG) {
                NodeExecutionContext.progress(
                        "security ruleId=" + finding.ruleId()
                                + " severity=" + finding.severity().name()
                                + " source=" + source);
            }
            recordSecurityViolation(state, source, finding, runId);
        }
        return assessment.decision() != SecurityDecision.BLOCK;
    }

    private AgentState failedSecurityState(AgentState state, String source) {
        return state
                .withVariable(ERROR_KEY, "Prompt 安全检查阻断: source=" + source)
                .withVariable(ROUTE_KEY, FAILED_ROUTE)
                .withTraceEntry("planner");
    }

    private void recordSecurityViolation(
            AgentState state, String source, SecurityFinding finding, UUID runId) {
        SecurityViolation violation = new SecurityViolation(
                UUID.randomUUID(),
                runId,
                state.variables().getOrDefault(USER_ID_KEY, "unknown"),
                "planner",
                Optional.empty(),
                SecurityViolationType.PROMPT_INJECTION,
                finding.severity(),
                finding.ruleId(),
                finding.summary(),
                Instant.now());
        try {
            securityViolationSink.record(violation);
        } catch (RuntimeException exception) {
            LOGGER.error("Planner 安全违规持久化失败 ruleId={} source={}",
                    finding.ruleId(), source, exception);
        }
    }

    private UUID currentRunId() {
        return NodeExecutionContext.current().map(NodeExecutionContext::runId)
                .orElseGet(UUID::randomUUID);
    }
}
