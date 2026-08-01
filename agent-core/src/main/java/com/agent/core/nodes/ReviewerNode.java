package com.agent.core.nodes;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.Node;
import com.agent.core.llm.ChatMessage;
import com.agent.core.llm.ModelRequest;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.RoutedCompletion;
import com.agent.core.llm.TaskType;
import com.agent.sandbox.browser.BrowserAutomation;
import com.agent.sandbox.browser.BrowserScreenshot;
import com.agent.sandbox.browser.NavigationResult;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
    public static final String ERROR_KEY = "reviewer.error";

    private static final String SYSTEM_INSTRUCTION = """
            你是最终质量审查节点。请结合测试日志、页面 DOM 和截图进行判断。
            只能返回一个完整 JSON 对象，并且只能包含 approved、summary、feedback 三个字段。
            approved 必须是 boolean，summary 与 feedback 必须是字符串。
            """;

    private final BrowserAutomation browserAutomation;
    private final ModelRouter modelRouter;
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
        Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
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
        Objects.requireNonNull(state, "state 不能为空");
        try {
            URI requestedUri = URI.create(requireUrl(state));
            validateOpsEvidence(state.variables());
            NavigationResult navigation = await(
                    browserAutomation.navigate(requestedUri, browserTimeout));
            String dom = await(browserAutomation.extractDom());
            BrowserScreenshot screenshot = await(
                    browserAutomation.screenshot(browserTimeout));
            String evidence = buildEvidence(
                    Objects.requireNonNull(navigation, "导航结果不能为空"),
                    Objects.requireNonNull(dom, "DOM 不能为空"),
                    state.variables());
            String imageUrl = "data:image/png;base64,"
                    + Base64.getEncoder().encodeToString(
                            Objects.requireNonNull(screenshot, "截图不能为空").pngBytes());
            ModelRequest request = new ModelRequest(
                    List.of(
                            ChatMessage.system(SYSTEM_INSTRUCTION),
                            ChatMessage.userMultimodal(List.of(
                                    new ChatMessage.TextPart(evidence),
                                    new ChatMessage.ImageUrlPart(new ChatMessage.ImageUrl(
                                            imageUrl,
                                            ChatMessage.ImageDetail.HIGH))))),
                    List.of(),
                    null,
                    null);
            RoutedCompletion completion = modelRouter.complete(TaskType.VISION, request);
            ReviewerDecision decision = parseDecision(completion);
            return state
                    .withVariable(APPROVED_KEY, Boolean.toString(decision.approved()))
                    .withVariable(SUMMARY_KEY, decision.summary())
                    .withVariable(FEEDBACK_KEY, decision.feedback())
                    .withVariable(MODEL_KEY, completion.model())
                    .withTraceEntry("reviewer");
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return state
                    .withVariable(ERROR_KEY, stackTrace(exception))
                    .withTraceEntry("reviewer");
        }
    }

    private ReviewerDecision parseDecision(RoutedCompletion completion) throws Exception {
        ChatMessage message = completion.response().choices().getFirst().message();
        if (!(message.content() instanceof ChatMessage.TextContent textContent)) {
            throw new IllegalStateException("审查模型响应 content 必须是 TextContent");
        }
        return decisionReader.readValue(textContent.text());
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

    private String requireUrl(AgentState state) {
        String url = state.variables().get(URL_KEY);
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("缺少状态变量: " + URL_KEY);
        }
        return url;
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
}
