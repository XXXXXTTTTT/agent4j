package com.agent.core.nodes;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.Node;
import com.agent.core.engine.NodeExecutionContext;
import com.agent.core.harness.HarnessHookException;
import com.agent.core.llm.ChatMessage;
import com.agent.core.llm.ModelRequest;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.RoutedCompletion;
import com.agent.core.llm.TaskType;
import com.agent.sandbox.browser.BrowserAutomation;
import com.agent.sandbox.browser.BrowserScreenshot;
import com.agent.sandbox.browser.NavigationResult;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/** 使用浏览器证据与 Ops 日志执行多模态质量审查的节点。 */
public final class ReviewerNode implements Node {

    public static final String URL_KEY = "reviewer.url";
    public static final String APPROVED_KEY = "reviewer.approved";
    public static final String SUMMARY_KEY = "reviewer.summary";
    public static final String FEEDBACK_KEY = "reviewer.feedback";
    public static final String MODEL_KEY = "reviewer.model";
    public static final String REQUEST_KEY = "reviewer.request";
    public static final String RESPONSE_KEY = "reviewer.response";
    public static final String ERROR_KEY = "reviewer.error";
    public static final String FINAL_URL_KEY = "reviewer.finalUrl";
    public static final String DOM_KEY = "reviewer.dom";
    public static final String SCREENSHOT_DATA_URL_KEY = "reviewer.screenshotDataUrl";

    private static final String SYSTEM_INSTRUCTION = """
            你是最终质量审查节点。请结合测试日志、页面 DOM 和截图进行判断。
            只能返回一个完整 JSON 对象，并且只能包含 approved、summary、feedback 三个字段。
            approved 必须是 boolean，summary 与 feedback 必须是字符串。
            """;

    private final BrowserAutomation browserAutomation;
    private final ModelRouter modelRouter;
    private final ObjectMapper objectMapper;
    private final Duration browserTimeout;
    private final ObjectReader decisionReader;

    /**
     * 创建多模态审查节点。
     *
     * @param browserAutomation 浏览器自动化协议
     * @param modelRouter       模型路由器
     * @param objectMapper      JSON 映射器
     * @param browserTimeout    浏览器操作统一超时
     */
    public ReviewerNode(
            BrowserAutomation browserAutomation,
            ModelRouter modelRouter,
            ObjectMapper objectMapper,
            Duration browserTimeout) {
        this.browserAutomation = Objects.requireNonNull(
                browserAutomation, "browserAutomation 不能为空");
        this.modelRouter = Objects.requireNonNull(modelRouter, "modelRouter 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.browserTimeout = Objects.requireNonNull(
                browserTimeout, "browserTimeout 不能为空");
        if (browserTimeout.isZero() || browserTimeout.isNegative()) {
            throw new IllegalArgumentException("browserTimeout 必须大于 0");
        }
        this.decisionReader = objectMapper.readerFor(ReviewerDecision.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);
    }

    /**
     * 收集页面与 Ops 证据，执行视觉模型审查并返回新状态。
     *
     * @param state 输入状态
     * @return 包含审查结果或完整错误堆栈的新状态
     */
    @Override
    public AgentState execute(AgentState state) {
        return executeReview(state, false);
    }

    /** 在 Run 上下文中执行并发布浏览器证据工具事件。 */
    @Override
    public AgentState execute(NodeExecutionContext context, AgentState state) {
        Objects.requireNonNull(context, "context 不能为空");
        boolean harness = NodeExecutionContext.current()
                .filter(context::equals)
                .isPresent();
        return executeReview(state, harness);
    }

    private AgentState executeReview(AgentState state, boolean harness) {
        Objects.requireNonNull(state, "state 不能为空");
        AgentState evidenceState = state;
        try {
            NodeExecutionContext.progress("正在收集测试与页面审查证据");
            validateOpsEvidence(state.variables());
            String configuredUrl = state.variables().get(URL_KEY);
            String evidence;
            String imageUrl = null;
            if (configuredUrl == null || configuredUrl.isBlank()) {
                evidence = buildCodeEvidence(state.variables());
            } else {
                URI requestedUri = URI.create(configuredUrl);
                BrowserEvidence browserEvidence = harness
                        ? NodeExecutionContext.callTool(
                                "browser.evidence",
                                Map.of("url", configuredUrl),
                                () -> collectBrowserEvidence(requestedUri))
                        : collectBrowserEvidence(requestedUri);
                NavigationResult navigation = browserEvidence.navigation();
                String dom = browserEvidence.dom();
                BrowserScreenshot screenshot = browserEvidence.screenshot();
                evidence = buildEvidence(
                        Objects.requireNonNull(navigation, "导航结果不能为空"),
                        Objects.requireNonNull(dom, "DOM 不能为空"),
                        state.variables());
                imageUrl = "data:image/png;base64,"
                        + Base64.getEncoder().encodeToString(
                                Objects.requireNonNull(screenshot, "截图不能为空").pngBytes());
                evidenceState = evidenceState
                        .withVariable(FINAL_URL_KEY, navigation.finalUrl().toString())
                        .withVariable(DOM_KEY, dom)
                        .withVariable(SCREENSHOT_DATA_URL_KEY, imageUrl);
            }
            evidenceState = evidenceState.withVariable(REQUEST_KEY, evidence);
            ChatMessage userMessage = imageUrl == null
                    ? ChatMessage.user(evidence)
                    : ChatMessage.userMultimodal(List.of(
                            new ChatMessage.TextPart(evidence),
                            new ChatMessage.ImageUrlPart(new ChatMessage.ImageUrl(
                                    imageUrl,
                                    ChatMessage.ImageDetail.HIGH))));
            ModelRequest request = new ModelRequest(
                    List.of(
                            ChatMessage.system(SYSTEM_INSTRUCTION),
                            userMessage),
                    List.of(),
                    null,
                    null);
            TaskType reviewTaskType = imageUrl == null ? TaskType.CODE : TaskType.VISION;
            RoutedCompletion completion = modelRouter.complete(reviewTaskType, request);
            NodeExecutionContext.progress("审查模型已返回质量判断");
            ReviewerDecision decision = parseDecision(completion);
            ChatMessage message = completion.response().choices().getFirst().message();
            if (!(message.content() instanceof ChatMessage.TextContent textContent)) {
                throw new IllegalStateException("审查模型响应 content 必须是 TextContent");
            }
            String finalResponse = buildFinalResponse(evidenceState.variables(), decision);
            return evidenceState
                    .withVariable(RESPONSE_KEY, textContent.text())
                    .withVariable(APPROVED_KEY, Boolean.toString(decision.approved()))
                    .withVariable(SUMMARY_KEY, decision.summary())
                    .withVariable(FEEDBACK_KEY, decision.feedback())
                    .withVariable(MODEL_KEY, completion.model())
                    .withVariable(PlannerNode.FINAL_RESPONSE_KEY, finalResponse)
                    .withTraceEntry("reviewer");
        } catch (HarnessHookException exception) {
            throw exception;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return evidenceState
                    .withVariable(ERROR_KEY, stackTrace(exception))
                    .withTraceEntry("reviewer");
        }
    }

    private BrowserEvidence collectBrowserEvidence(URI requestedUri)
            throws InterruptedException, ExecutionException {
        NavigationResult navigation = await(
                browserAutomation.navigate(requestedUri, browserTimeout));
        String dom = await(browserAutomation.extractDom(browserTimeout));
        BrowserScreenshot screenshot = await(browserAutomation.screenshot(browserTimeout));
        return new BrowserEvidence(
                Objects.requireNonNull(navigation, "导航结果不能为空"),
                Objects.requireNonNull(dom, "DOM 不能为空"),
                Objects.requireNonNull(screenshot, "截图不能为空"));
    }

    private ReviewerDecision parseDecision(RoutedCompletion completion) throws Exception {
        ChatMessage message = completion.response().choices().getFirst().message();
        if (!(message.content() instanceof ChatMessage.TextContent textContent)) {
            throw new IllegalStateException("审查模型响应 content 必须是 TextContent");
        }
        validateDecisionTypes(objectMapper.readTree(textContent.text()));
        return decisionReader.readValue(textContent.text());
    }

    private void validateDecisionTypes(JsonNode decisionNode) {
        if (decisionNode == null || !decisionNode.isObject()) {
            throw new IllegalArgumentException("审查决策必须是 JSON 对象");
        }
        requireDecisionType(decisionNode, "approved", JsonNode::isBoolean, "boolean");
        requireDecisionType(decisionNode, "summary", JsonNode::isTextual, "string");
        requireDecisionType(decisionNode, "feedback", JsonNode::isTextual, "string");
    }

    private void requireDecisionType(
            JsonNode decisionNode,
            String field,
            java.util.function.Predicate<JsonNode> typeCheck,
            String expectedType) {
        JsonNode fieldValue = decisionNode.get(field);
        if (fieldValue == null || !typeCheck.test(fieldValue)) {
            throw new IllegalArgumentException(
                    "审查决策字段 " + field + " 必须是 " + expectedType);
        }
    }

    private String buildEvidence(
            NavigationResult navigation,
            String dom,
            Map<String, String> variables) {
        StringBuilder evidence = new StringBuilder()
                .append("最终 URI:\n")
                .append(navigation.finalUrl())
                .append("\n完整 DOM:\n")
                .append(dom)
                .append("\nOps 证据:\n");
        appendIfPresent(evidence, variables, OpsNode.EXIT_CODE_KEY);
        appendIfPresent(evidence, variables, OpsNode.STDOUT_KEY);
        appendIfPresent(evidence, variables, OpsNode.STDERR_KEY);
        appendIfPresent(evidence, variables, OpsNode.TIMED_OUT_KEY);
        appendIfPresent(evidence, variables, OpsNode.ERROR_KEY);
        return evidence.toString();
    }

    private String buildCodeEvidence(Map<String, String> variables) {
        StringBuilder evidence = new StringBuilder("代码与 Ops 证据:\n");
        appendIfPresent(evidence, variables, CoderNode.UNIFIED_DIFF_KEY);
        appendIfPresent(evidence, variables, CoderNode.UPDATED_FILES_KEY);
        appendIfPresent(evidence, variables, CoderNode.COMMAND_KEY);
        appendIfPresent(evidence, variables, OpsNode.COMMAND_KEY);
        appendIfPresent(evidence, variables, OpsNode.EXIT_CODE_KEY);
        appendIfPresent(evidence, variables, OpsNode.STDOUT_KEY);
        appendIfPresent(evidence, variables, OpsNode.STDERR_KEY);
        appendIfPresent(evidence, variables, OpsNode.TIMED_OUT_KEY);
        appendIfPresent(evidence, variables, OpsNode.ERROR_KEY);
        appendIfPresent(evidence, variables, CoderNode.ERROR_KEY);
        return evidence.toString();
    }

    private String buildFinalResponse(
            Map<String, String> variables,
            ReviewerDecision decision) {
        StringBuilder response = new StringBuilder(decision.approved()
                ? "代码任务已完成并通过审查。"
                : "代码任务已执行，但审查未通过。");
        appendFinalSection(response, "修改文件", variables.get(CoderNode.UPDATED_FILES_KEY));
        appendFinalSection(response, "修改内容", variables.get(CoderNode.SUMMARY_KEY));
        appendFinalSection(response, "验证命令", variables.get(OpsNode.COMMAND_KEY));
        String exitCode = variables.get(OpsNode.EXIT_CODE_KEY);
        if (exitCode != null && !exitCode.isBlank()) {
            response.append("\n\n退出码：").append(exitCode);
        }
        appendFinalSection(response, "审查结论", decision.summary());
        appendFinalSection(response, "审查反馈", decision.feedback());
        return response.toString();
    }

    private void appendFinalSection(
            StringBuilder response,
            String title,
            String value) {
        if (value != null && !value.isBlank()) {
            response.append("\n\n").append(title).append("：\n").append(value);
        }
    }

    private void appendIfPresent(
            StringBuilder evidence,
            Map<String, String> variables,
            String key) {
        if (variables.containsKey(key)) {
            evidence.append(key)
                    .append("=\n")
                    .append(variables.get(key))
                    .append('\n');
        }
    }

    private void validateOpsEvidence(Map<String, String> variables) {
        boolean hasCompleteResult = variables.containsKey(OpsNode.EXIT_CODE_KEY)
                && variables.containsKey(OpsNode.STDOUT_KEY)
                && variables.containsKey(OpsNode.STDERR_KEY)
                && variables.containsKey(OpsNode.TIMED_OUT_KEY);
        String opsError = variables.get(OpsNode.ERROR_KEY);
        boolean hasError = opsError != null && !opsError.isBlank();
        if (!hasCompleteResult && !hasError) {
            throw new IllegalArgumentException("缺少完整 Ops 结果或非空 ops.error");
        }
    }

    private <T> T await(CompletableFuture<T> future)
            throws InterruptedException, ExecutionException {
        return Objects.requireNonNull(future, "浏览器 future 不能为空").get();
    }

    private String stackTrace(Exception exception) {
        StringWriter writer = new StringWriter();
        exception.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private record ReviewerDecision(boolean approved, String summary, String feedback) {

        /** 校验模型返回的审查文本。 */
        private ReviewerDecision {
            Objects.requireNonNull(summary, "summary 不能为空");
            Objects.requireNonNull(feedback, "feedback 不能为空");
        }
    }

    private record BrowserEvidence(
            NavigationResult navigation,
            String dom,
            BrowserScreenshot screenshot) {
    }
}
