package com.agent.core.tool.builtin;

import com.agent.core.gui.BrowserSessionRegistry;
import com.agent.core.intent.RequiredCapability;
import com.agent.core.tool.ToolCall;
import com.agent.core.tool.ToolDefinition;
import com.agent.core.tool.ToolInvocationContext;
import com.agent.core.tool.ToolRiskLevel;
import com.agent.sandbox.browser.BrowserAutomation;
import com.agent.sandbox.browser.BrowserEvidence;
import com.agent.sandbox.browser.BrowserEvidenceSelector;
import com.agent.sandbox.browser.NavigationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/** 创建按 Run 会话执行的受治理浏览器工具。 */
public final class BrowserToolDefinitions {

    public static final String NAVIGATE_NAME = "browser.navigate";
    public static final String CLICK_NAME = "browser.click";
    public static final String FILL_NAME = "browser.fill";
    public static final String SCROLL_NAME = "browser.scroll";
    public static final String EVIDENCE_NAME = "browser.evidence";
    public static final int MAX_TEXT_CODE_POINTS = 16_384;
    public static final int MAX_SCROLL_DELTA = 10_000;
    public static final int MAX_EVIDENCE_DOM_CODE_POINTS = 64_000;
    public static final int MAX_EVIDENCE_VISIBLE_TEXT_CODE_POINTS = 16_384;
    public static final int MAX_EVIDENCE_SCREENSHOT_BYTES = 4 * 1024 * 1024;

    private BrowserToolDefinitions() {
    }

    /** 创建五个严格 Schema 的浏览器工具定义。 */
    public static List<ToolDefinition> definitions(
            BrowserSessionRegistry sessions,
            ObjectMapper objectMapper,
            Duration timeout) {
        Objects.requireNonNull(sessions, "sessions 不能为空");
        Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        requirePositive(timeout);
        return List.of(
                navigateDefinition(sessions, objectMapper, timeout),
                clickDefinition(sessions, objectMapper, timeout),
                fillDefinition(sessions, objectMapper, timeout),
                scrollDefinition(sessions, objectMapper, timeout),
                evidenceDefinition(sessions, objectMapper, timeout));
    }

    private static ToolDefinition navigateDefinition(
            BrowserSessionRegistry sessions,
            ObjectMapper mapper,
            Duration timeout) {
        ObjectNode schema = objectSchema(mapper);
        schema.withObject("properties").set("url", stringSchema(mapper, 2_048));
        schema.withArray("required").add("url");
        return definition(NAVIGATE_NAME, "导航到绝对 HTTP 或 HTTPS URL",
                schema, ToolRiskLevel.MEDIUM, timeout,
                (call, context) -> navigate(sessions, mapper, timeout, call, context));
    }

    private static ToolDefinition clickDefinition(
            BrowserSessionRegistry sessions,
            ObjectMapper mapper,
            Duration timeout) {
        ObjectNode schema = selectorSchema(mapper);
        return definition(CLICK_NAME, "点击精确选择器定位的页面元素",
                schema, ToolRiskLevel.MEDIUM, timeout,
                (call, context) -> {
                    BrowserAutomation browser = sessions.require(context.runId());
                    return completed(mapper, browser, timeout,
                        () -> browser.click(
                                text(call, "selector"), timeout));
                });
    }

    private static ToolDefinition fillDefinition(
            BrowserSessionRegistry sessions,
            ObjectMapper mapper,
            Duration timeout) {
        ObjectNode schema = selectorSchema(mapper);
        schema.withObject("properties").set(
                "value", boundedStringSchema(mapper, MAX_TEXT_CODE_POINTS));
        schema.withArray("required").add("value");
        return definition(FILL_NAME, "向精确选择器定位的输入控件填充值",
                schema, ToolRiskLevel.MEDIUM, timeout,
                (call, context) -> {
                    BrowserAutomation browser = sessions.require(context.runId());
                    return completed(mapper, browser, timeout,
                        () -> browser.fill(
                                text(call, "selector"), value(call, "value"), timeout));
                });
    }

    private static ToolDefinition scrollDefinition(
            BrowserSessionRegistry sessions,
            ObjectMapper mapper,
            Duration timeout) {
        ObjectNode schema = objectSchema(mapper);
        schema.withObject("properties").set("deltaY", mapper.createObjectNode()
                .put("type", "integer")
                .put("minimum", -MAX_SCROLL_DELTA)
                .put("maximum", MAX_SCROLL_DELTA));
        schema.withArray("required").add("deltaY");
        return definition(SCROLL_NAME, "按精确垂直偏移量滚动当前页面",
                schema, ToolRiskLevel.LOW, timeout,
                (call, context) -> {
                    BrowserAutomation browser = sessions.require(context.runId());
                    return completed(mapper, browser, timeout,
                        () -> browser.scroll(
                                call.arguments().path("deltaY").intValue(), timeout));
                });
    }

    private static ToolDefinition evidenceDefinition(
            BrowserSessionRegistry sessions,
            ObjectMapper mapper,
            Duration timeout) {
        ObjectNode schema = selectorSchema(mapper);
        return definition(EVIDENCE_NAME, "采集完整页面或指定元素的 DOM 与 PNG 证据",
                schema, ToolRiskLevel.LOW, timeout,
                (call, context) -> evidence(sessions, mapper, timeout, call, context));
    }

    private static JsonNode navigate(
            BrowserSessionRegistry sessions,
            ObjectMapper mapper,
            Duration timeout,
            ToolCall call,
            ToolInvocationContext context) {
        URI url = validateUrl(text(call, "url"));
        NavigationResult result = await(
                sessions.require(context.runId()).navigate(url, timeout));
        ObjectNode output = mapper.createObjectNode()
                .put("finalUrl", result.finalUrl().toString());
        if (result.statusCode().isPresent()) {
            output.put("statusCode", result.statusCode().getAsInt());
        } else {
            output.putNull("statusCode");
        }
        return output;
    }

    private static JsonNode completed(
            ObjectMapper mapper,
            BrowserAutomation browser,
            Duration timeout,
            Supplier<java.util.concurrent.CompletableFuture<Void>> action) {
        Objects.requireNonNull(mapper, "objectMapper 不能为空");
        Objects.requireNonNull(browser, "browser 不能为空");
        Objects.requireNonNull(timeout, "timeout 不能为空");
        await(action.get());
        return mapper.createObjectNode().put("completed", true);
    }

    private static JsonNode evidence(
            BrowserSessionRegistry sessions,
            ObjectMapper mapper,
            Duration timeout,
            ToolCall call,
            ToolInvocationContext context) {
        String selector = text(call, "selector");
        BrowserEvidenceSelector evidenceSelector = BrowserEvidenceSelector.PAGE_SELECTOR.equals(selector)
                ? BrowserEvidenceSelector.page()
                : BrowserEvidenceSelector.locator(selector);
        BrowserEvidence evidence = await(sessions.require(context.runId())
                .capture(evidenceSelector, timeout));
        byte[] png = evidence.screenshot().pngBytes();
        if (png.length > MAX_EVIDENCE_SCREENSHOT_BYTES) {
            throw new IllegalArgumentException("截图超过证据大小上限");
        }
        String dom = truncateCodePoints(
                evidence.dom(), MAX_EVIDENCE_DOM_CODE_POINTS);
        String visibleText = truncateCodePoints(
                evidence.visibleText(), MAX_EVIDENCE_VISIBLE_TEXT_CODE_POINTS);
        return mapper.createObjectNode()
                .put("finalUrl", evidence.finalUrl().toString())
                .put("selector", evidence.selector())
                .put("dom", dom)
                .put("visibleText", visibleText)
                .put("screenshotDataUrl", "data:image/png;base64,"
                        + Base64.getEncoder().encodeToString(png))
                .put("domSha256", sha256(dom.getBytes(StandardCharsets.UTF_8)))
                .put("screenshotSha256", sha256(png));
    }

    private static ToolDefinition definition(
            String name,
            String description,
            ObjectNode schema,
            ToolRiskLevel riskLevel,
            Duration timeout,
            com.agent.core.tool.ToolHandler handler) {
        return new ToolDefinition(
                name,
                description,
                schema,
                Set.of(RequiredCapability.BROWSER),
                riskLevel,
                timeout,
                handler);
    }

    private static ObjectNode selectorSchema(ObjectMapper mapper) {
        ObjectNode schema = objectSchema(mapper);
        schema.withObject("properties").set("selector", stringSchema(
                mapper, BrowserEvidenceSelector.MAX_LOCATOR_CODE_POINTS));
        schema.withArray("required").add("selector");
        return schema;
    }

    private static ObjectNode objectSchema(ObjectMapper mapper) {
        return mapper.createObjectNode()
                .put("type", "object")
                .put("additionalProperties", false)
                .set("properties", mapper.createObjectNode());
    }

    private static ObjectNode stringSchema(ObjectMapper mapper, int maximumLength) {
        return boundedStringSchema(mapper, maximumLength)
                .put("minLength", 1);
    }

    private static ObjectNode boundedStringSchema(ObjectMapper mapper, int maximumLength) {
        return mapper.createObjectNode()
                .put("type", "string")
                .put("maxLength", maximumLength);
    }

    private static String text(ToolCall call, String field) {
        String value = call.arguments().path(field).textValue();
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    private static String value(ToolCall call, String field) {
        JsonNode value = call.arguments().get(field);
        if (value == null || !value.isTextual()
                || value.textValue().codePointCount(0, value.textValue().length())
                > MAX_TEXT_CODE_POINTS) {
            throw new IllegalArgumentException(field + " 必须是有界字符串");
        }
        return value.textValue();
    }

    private static URI validateUrl(String value) {
        URI url = URI.create(value);
        String scheme = url.getScheme();
        if (!url.isAbsolute() || !("http".equals(scheme) || "https".equals(scheme))) {
            throw new IllegalArgumentException("url 必须是绝对 http 或 https URI");
        }
        return url;
    }

    private static <T> T await(java.util.concurrent.CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("浏览器异步操作失败", cause);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("JDK 必须提供 SHA-256", exception);
        }
    }

    private static String truncateCodePoints(String value, int maximumCodePoints) {
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= maximumCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maximumCodePoints));
    }

    private static void requirePositive(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout 不能为空");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须为正数");
        }
    }
}
