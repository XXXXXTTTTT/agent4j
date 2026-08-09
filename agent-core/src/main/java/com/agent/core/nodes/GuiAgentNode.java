package com.agent.core.nodes;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.Node;
import com.agent.core.engine.NodeExecutionContext;
import com.agent.core.gui.BrowserActionDecision;
import com.agent.core.gui.BrowserSessionRegistry;
import com.agent.core.harness.HarnessHookException;
import com.agent.core.intent.RequiredCapability;
import com.agent.core.llm.ChatMessage;
import com.agent.core.llm.ModelRequest;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.RoutedCompletion;
import com.agent.core.llm.TaskType;
import com.agent.core.tool.HarnessToolExecutor;
import com.agent.core.tool.ToolCall;
import com.agent.core.tool.ToolInvocationContext;
import com.agent.core.tool.ToolRegistry;
import com.agent.core.tool.ToolResult;
import com.agent.core.tool.ToolResultStatus;
import com.agent.core.tool.builtin.BrowserToolDefinitions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 基于视觉决策、受治理工具和证据引用执行浏览器任务的节点。 */
public final class GuiAgentNode implements Node {

    public static final String URL_KEY = "gui.url";
    public static final String GOAL_KEY = "gui.goal";
    public static final String STEP_KEY = "gui.step";
    public static final String ACTIONS_KEY = "gui.actions";
    public static final String EVIDENCE_KEY = "gui.evidence";
    public static final String FINAL_URL_KEY = "gui.finalUrl";
    public static final String DOM_KEY = "gui.dom";
    public static final String SCREENSHOT_DATA_URL_KEY = "gui.screenshotDataUrl";
    public static final String SUMMARY_KEY = "gui.summary";
    public static final String MODEL_KEY = "gui.model";
    public static final String REQUEST_KEY = "gui.request";
    public static final String RESPONSE_KEY = "gui.response";
    public static final String ERROR_KEY = "gui.error";

    private static final String SYSTEM_INSTRUCTION = """
            你是浏览器 GUI Agent。根据当前目标、动作历史、DOM 和截图选择下一步。
            只能返回一个完整 JSON 对象，字段必须精确为 action、selector、value、deltaY、
            evidenceSelector、reason、summary、evidenceRefs。
            action 只能是 click、fill、scroll、done。
            done 必须引用当前上下文中已经存在的证据 ID，禁止在没有证据时宣告完成。
            """;

    private final BrowserSessionRegistry sessions;
    private final ModelRouter modelRouter;
    private final ObjectMapper objectMapper;
    private final HarnessToolExecutor toolExecutor;
    private final int maxSteps;

    /** 创建证据驱动的浏览器 Agent 节点。 */
    public GuiAgentNode(
            BrowserSessionRegistry sessions,
            ModelRouter modelRouter,
            ObjectMapper objectMapper,
            ToolRegistry toolRegistry,
            Duration browserTimeout,
            int maxSteps) {
        this.sessions = Objects.requireNonNull(sessions, "sessions 不能为空");
        this.modelRouter = Objects.requireNonNull(modelRouter, "modelRouter 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        Objects.requireNonNull(toolRegistry, "toolRegistry 不能为空");
        this.toolExecutor = new HarnessToolExecutor(toolRegistry);
        Objects.requireNonNull(browserTimeout, "browserTimeout 不能为空");
        if (browserTimeout.isZero() || browserTimeout.isNegative()) {
            throw new IllegalArgumentException("browserTimeout 必须为正数");
        }
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps 必须大于 0");
        }
        this.maxSteps = maxSteps;
    }

    /** 执行浏览器动作循环并返回完成状态或完整错误栈。 */
    @Override
    public AgentState execute(AgentState state) {
        return executeGui(state);
    }

    /** 在 Run 上下文中执行浏览器动作循环。 */
    @Override
    public AgentState execute(NodeExecutionContext context, AgentState state) {
        Objects.requireNonNull(context, "context 不能为空");
        return executeGui(state);
    }

    private AgentState executeGui(AgentState state) {
        Objects.requireNonNull(state, "state 不能为空");
        ExecutionArtifacts artifacts = new ExecutionArtifacts(state, objectMapper);
        BrowserActionDecision completion = null;
        Throwable failure = null;
        HarnessHookException harnessFailure = null;
        boolean sessionOpened = false;
        NodeExecutionContext context = null;
        try {
            context = NodeExecutionContext.current()
                    .orElseThrow(() -> new IllegalStateException("GuiAgentNode 必须在节点上下文中执行"));
            String url = requireVariable(state, ReviewerNode.URL_KEY);
            String goal = requireVariable(state, PlannerNode.TASK_KEY);
            artifacts.state = state
                    .withVariable(URL_KEY, url)
                    .withVariable(GOAL_KEY, goal);
            sessions.open(context.runId());
            sessionOpened = true;
            NodeExecutionContext.progress("正在打开目标页面并采集初始证据");
            executeRequiredTool(
                    artifacts,
                    context,
                    BrowserToolDefinitions.NAVIGATE_NAME,
                    "gui-navigate",
                    objectMapper.createObjectNode().put("url", url));
            captureEvidence(artifacts, context, "page");
            completion = runActionLoop(artifacts, context, goal);
        } catch (HarnessHookException exception) {
            failure = exception;
            harnessFailure = exception;
        } catch (Throwable exception) {
            failure = exception;
        }

        if (sessionOpened && context != null) {
            try {
                sessions.close(context.runId());
            } catch (RuntimeException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        if (harnessFailure != null) {
            throw harnessFailure;
        }
        if (failure != null) {
            return artifacts.failure(failure);
        }
        return artifacts.success(Objects.requireNonNull(completion, "completion 不能为空"));
    }

    private BrowserActionDecision runActionLoop(
            ExecutionArtifacts artifacts,
            NodeExecutionContext context,
            String goal) throws Exception {
        for (int step = 1; step <= maxSteps; step++) {
            artifacts.step = step;
            artifacts.state = artifacts.state.withVariable(STEP_KEY, Integer.toString(step));
            NodeExecutionContext.progress("正在生成第 " + step + " 步浏览器决策");
            String requestText = buildRequest(goal, artifacts);
            artifacts.state = artifacts.state.withVariable(REQUEST_KEY, requestText);
            ModelRequest request = new ModelRequest(
                    List.of(
                            ChatMessage.system(SYSTEM_INSTRUCTION),
                            ChatMessage.userMultimodal(List.of(
                                    new ChatMessage.TextPart(requestText),
                                    new ChatMessage.ImageUrlPart(new ChatMessage.ImageUrl(
                                            artifacts.lastEvidence.path("screenshotDataUrl").textValue(),
                                            ChatMessage.ImageDetail.HIGH))))),
                    List.of(),
                    null,
                    0.0);
            RoutedCompletion routed = modelRouter.complete(TaskType.VISION, request);
            String response = responseText(routed);
            artifacts.state = artifacts.state
                    .withVariable(MODEL_KEY, routed.model())
                    .withVariable(RESPONSE_KEY, response);
            BrowserActionDecision decision = BrowserActionDecision.parse(
                    objectMapper, response);
            if (decision.action() == BrowserActionDecision.Action.DONE) {
                validateEvidenceReferences(decision.evidenceRefs(), artifacts.evidenceIds);
                artifacts.actions.add(actionRecord(decision)
                        .put("toolStatus", "NOT_APPLICABLE")
                        .put("toolError", ""));
                return decision;
            }
            executeAction(artifacts, context, decision, step);
        }
        throw new IllegalStateException("GUI 动作循环达到 maxSteps=" + maxSteps);
    }

    private void executeAction(
            ExecutionArtifacts artifacts,
            NodeExecutionContext context,
            BrowserActionDecision decision,
            int step) throws Exception {
        String toolName;
        ObjectNode arguments = objectMapper.createObjectNode();
        switch (decision.action()) {
            case CLICK -> {
                toolName = BrowserToolDefinitions.CLICK_NAME;
                arguments.put("selector", decision.selector());
            }
            case FILL -> {
                toolName = BrowserToolDefinitions.FILL_NAME;
                arguments.put("selector", decision.selector())
                        .put("value", decision.value());
            }
            case SCROLL -> {
                toolName = BrowserToolDefinitions.SCROLL_NAME;
                arguments.put("deltaY", decision.deltaY());
            }
            case DONE -> throw new IllegalStateException("done 不能作为工具动作执行");
            default -> throw new IllegalStateException("未知浏览器动作");
        }
        ToolResult result = executeTool(
                artifacts,
                context,
                toolName,
                "gui-" + decision.action().jsonValue() + "-" + step,
                arguments);
        ObjectNode action = actionRecord(decision)
                .put("toolName", toolName)
                .put("toolStatus", result.status().name())
                .put("toolError", result.errorStack());
        artifacts.actions.add(action);
        if (result.status() == ToolResultStatus.SUCCEEDED) {
            captureEvidence(artifacts, context, decision.evidenceSelector());
        }
    }

    private void captureEvidence(
            ExecutionArtifacts artifacts,
            NodeExecutionContext context,
            String selector) throws Exception {
        ToolResult result = executeRequiredTool(
                artifacts,
                context,
                BrowserToolDefinitions.EVIDENCE_NAME,
                "gui-evidence-" + artifacts.evidence.size(),
                objectMapper.createObjectNode().put("selector", selector));
        JsonNode output = result.output();
        requireEvidenceOutput(output);
        String evidenceId = "evidence-" + artifacts.evidence.size();
        ObjectNode stored = objectMapper.createObjectNode().put("id", evidenceId);
        stored.setAll((ObjectNode) output);
        artifacts.evidence.add(stored);
        artifacts.evidenceIds.add(evidenceId);
        artifacts.lastEvidence = output;
        artifacts.state = artifacts.state
                .withVariable(EVIDENCE_KEY, artifacts.evidence.toString())
                .withVariable(FINAL_URL_KEY, output.path("finalUrl").textValue())
                .withVariable(DOM_KEY, output.path("dom").textValue())
                .withVariable(SCREENSHOT_DATA_URL_KEY,
                        output.path("screenshotDataUrl").textValue());
    }

    private ToolResult executeRequiredTool(
            ExecutionArtifacts artifacts,
            NodeExecutionContext context,
            String toolName,
            String callId,
            ObjectNode arguments) throws Exception {
        ToolResult result = executeTool(artifacts, context, toolName, callId, arguments);
        if (result.status() != ToolResultStatus.SUCCEEDED) {
            throw new IllegalStateException(
                    "浏览器工具执行失败: " + toolName + System.lineSeparator()
                            + result.errorStack());
        }
        return result;
    }

    private ToolResult executeTool(
            ExecutionArtifacts artifacts,
            NodeExecutionContext context,
            String toolName,
            String callId,
            ObjectNode arguments) throws Exception {
        Path workspace = Path.of(requireVariable(artifacts.state, CoderNode.WORKSPACE_PATH_KEY));
        ToolInvocationContext toolContext = new ToolInvocationContext(
                context.runId(),
                context.nodeName(),
                requireVariable(artifacts.state, PlannerNode.USER_ID_KEY),
                workspace,
                Set.of(RequiredCapability.BROWSER),
                false);
        return toolExecutor.execute(new ToolCall(callId, toolName, arguments), toolContext);
    }

    private ObjectNode actionRecord(BrowserActionDecision decision) {
        return objectMapper.valueToTree(decision);
    }

    private String buildRequest(String goal, ExecutionArtifacts artifacts) {
        return """
                用户目标:
                %s

                已执行动作:
                %s

                当前证据 ID: %s
                当前 URL: %s
                当前选择器: %s
                当前 DOM:
                %s
                """.formatted(
                goal,
                artifacts.actions,
                "evidence-" + (artifacts.evidence.size() - 1),
                artifacts.lastEvidence.path("finalUrl").textValue(),
                artifacts.lastEvidence.path("selector").textValue(),
                artifacts.lastEvidence.path("dom").textValue());
    }

    private String responseText(RoutedCompletion completion) {
        ChatMessage message = completion.response().choices().getFirst().message();
        if (!(message.content() instanceof ChatMessage.TextContent textContent)) {
            throw new IllegalStateException("GUI 模型响应 content 必须是 TextContent");
        }
        return textContent.text();
    }

    private void requireEvidenceOutput(JsonNode output) {
        if (!output.isObject()) {
            throw new IllegalStateException("browser.evidence 输出必须是 JSON object");
        }
        requireTextField(output, "finalUrl");
        requireTextField(output, "selector");
        requireTextField(output, "dom");
        requireTextField(output, "screenshotDataUrl");
        requireTextField(output, "domSha256");
        requireTextField(output, "screenshotSha256");
        URI.create(output.path("finalUrl").textValue());
    }

    private void requireTextField(JsonNode output, String field) {
        JsonNode value = output.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException("browser.evidence 的 " + field + " 必须是非空字符串");
        }
    }

    private void validateEvidenceReferences(List<String> references, Set<String> evidenceIds) {
        for (String reference : references) {
            if (!evidenceIds.contains(reference)) {
                throw new IllegalArgumentException("done 引用了不存在的证据: " + reference);
            }
        }
    }

    private static String requireVariable(AgentState state, String key) {
        String value = state.variables().get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少状态变量 " + key);
        }
        return value;
    }

    private static String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private final class ExecutionArtifacts {

        private AgentState state;
        private final ArrayNode actions;
        private final ArrayNode evidence;
        private final Set<String> evidenceIds = new HashSet<>();
        private JsonNode lastEvidence;
        private int step;

        private ExecutionArtifacts(AgentState state, ObjectMapper mapper) {
            this.state = state;
            this.actions = mapper.createArrayNode();
            this.evidence = mapper.createArrayNode();
        }

        private AgentState success(BrowserActionDecision decision) {
            return state
                    .withVariable(STEP_KEY, Integer.toString(step))
                    .withVariable(ACTIONS_KEY, actions.toString())
                    .withVariable(EVIDENCE_KEY, evidence.toString())
                    .withVariable(SUMMARY_KEY, decision.summary())
                    .withVariable(PlannerNode.FINAL_RESPONSE_KEY, decision.summary())
                    .withTraceEntry("gui");
        }

        private AgentState failure(Throwable throwable) {
            return state
                    .withVariable(STEP_KEY, Integer.toString(step))
                    .withVariable(ACTIONS_KEY, actions.toString())
                    .withVariable(EVIDENCE_KEY, evidence.toString())
                    .withVariable(ERROR_KEY, stackTrace(throwable))
                    .withTraceEntry("gui");
        }
    }
}
