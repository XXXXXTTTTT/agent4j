package com.agent.core.tool.builtin;

import com.agent.core.gui.BrowserSessionRegistry;
import com.agent.core.intent.RequiredCapability;
import com.agent.core.tool.DefaultToolRegistry;
import com.agent.core.tool.ToolCall;
import com.agent.core.tool.ToolDefinition;
import com.agent.core.tool.ToolInvocationContext;
import com.agent.core.tool.ToolResult;
import com.agent.core.tool.ToolResultStatus;
import com.agent.core.tool.ToolRiskLevel;
import com.agent.sandbox.browser.BrowserAutomation;
import com.agent.sandbox.browser.BrowserEvidence;
import com.agent.sandbox.browser.BrowserEvidenceSelector;
import com.agent.sandbox.browser.BrowserScreenshot;
import com.agent.sandbox.browser.NavigationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class BrowserToolDefinitionsTest {

    private static final UUID RUN_ID = UUID.fromString(
            "642380b4-e9a6-4be2-a0fc-cdf9bbc74d71");
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @TempDir
    Path workspace;

    @Test
    void definesFiveStrictBrowserToolsWithExactGovernance() {
        ObjectMapper mapper = new ObjectMapper();
        BrowserSessionRegistry sessions = new BrowserSessionRegistry(TestBrowser::new);

        List<ToolDefinition> definitions = BrowserToolDefinitions.definitions(
                sessions, mapper, TIMEOUT);

        assertThat(definitions).extracting(ToolDefinition::name).containsExactly(
                "browser.navigate",
                "browser.click",
                "browser.fill",
                "browser.scroll",
                "browser.evidence");
        assertThat(definitions).allSatisfy(definition -> {
            assertThat(definition.inputSchema().path("additionalProperties").booleanValue())
                    .isFalse();
            assertThat(definition.requiredCapabilities())
                    .containsExactly(RequiredCapability.BROWSER);
            assertThat(definition.timeout()).isEqualTo(TIMEOUT);
        });
        assertThat(definitions).filteredOn(definition -> definition.name().equals("browser.scroll")
                        || definition.name().equals("browser.evidence"))
                .extracting(ToolDefinition::riskLevel)
                .containsOnly(ToolRiskLevel.LOW);
        assertThat(definitions).filteredOn(definition -> !definition.name().equals("browser.scroll")
                        && !definition.name().equals("browser.evidence"))
                .extracting(ToolDefinition::riskLevel)
                .containsOnly(ToolRiskLevel.MEDIUM);
        ToolDefinition fill = definitions.stream()
                .filter(definition -> definition.name().equals("browser.fill"))
                .findFirst()
                .orElseThrow();
        assertThat(fill.inputSchema().path("properties").path("value").has("minLength"))
                .isFalse();
        sessions.close();
    }

    @Test
    void executesAllActionsAgainstTheRunSessionAndReturnsEvidenceHashes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TestBrowser browser = new TestBrowser();
        BrowserSessionRegistry sessions = new BrowserSessionRegistry(() -> browser);
        sessions.open(RUN_ID);
        try (DefaultToolRegistry tools = new DefaultToolRegistry()) {
            tools.registerAll(BrowserToolDefinitions.definitions(sessions, mapper, TIMEOUT));

            ToolResult navigation = execute(tools, mapper, "nav", "browser.navigate",
                    mapper.createObjectNode().put("url", "https://example.test/start"));
            ToolResult click = execute(tools, mapper, "click", "browser.click",
                    mapper.createObjectNode().put("selector", "#submit"));
            ToolResult clear = execute(tools, mapper, "clear", "browser.fill",
                    mapper.createObjectNode().put("selector", "#name").put("value", ""));
            ToolResult fill = execute(tools, mapper, "fill", "browser.fill",
                    mapper.createObjectNode().put("selector", "#name").put("value", "Agent4J"));
            ToolResult scroll = execute(tools, mapper, "scroll", "browser.scroll",
                    mapper.createObjectNode().put("deltaY", 500));
            ToolResult evidence = execute(tools, mapper, "evidence", "browser.evidence",
                    mapper.createObjectNode().put("selector", "#result"));

            assertThat(navigation.status()).isEqualTo(ToolResultStatus.SUCCEEDED);
            assertThat(navigation.output().path("finalUrl").textValue())
                    .isEqualTo("https://example.test/final");
            assertThat(navigation.output().path("statusCode").intValue()).isEqualTo(204);
            assertThat(click.output().path("completed").booleanValue()).isTrue();
            assertThat(fill.output().path("completed").booleanValue()).isTrue();
            assertThat(clear.output().path("completed").booleanValue()).isTrue();
            assertThat(scroll.output().path("completed").booleanValue()).isTrue();
            assertThat(evidence.status()).isEqualTo(ToolResultStatus.SUCCEEDED);
            assertThat(evidence.output().path("finalUrl").textValue())
                    .isEqualTo("https://example.test/final");
            assertThat(evidence.output().path("selector").textValue()).isEqualTo("#result");
            assertThat(evidence.output().path("dom").textValue()).isEqualTo("<div>ready</div>");
            assertThat(evidence.output().path("visibleText").textValue()).isEqualTo("ready");
            assertThat(evidence.output().path("screenshotDataUrl").textValue())
                    .isEqualTo("data:image/png;base64," + Base64.getEncoder()
                            .encodeToString(new byte[] {1, 2, 3}));
            assertThat(evidence.output().path("domSha256").textValue())
                    .isEqualTo(sha256("<div>ready</div>".getBytes(StandardCharsets.UTF_8)));
            assertThat(evidence.output().path("screenshotSha256").textValue())
                    .isEqualTo(sha256(new byte[] {1, 2, 3}));
            assertThat(browser.navigatedUrl).isEqualTo(URI.create("https://example.test/start"));
            assertThat(browser.clickedSelector).isEqualTo("#submit");
            assertThat(browser.filledSelector).isEqualTo("#name");
            assertThat(browser.filledValue).isEqualTo("Agent4J");
            assertThat(browser.deltaY).isEqualTo(500);
            assertThat(browser.evidenceSelector.selector()).isEqualTo("#result");
            assertThat(browser.timeouts).containsExactly(
                    TIMEOUT, TIMEOUT, TIMEOUT, TIMEOUT, TIMEOUT, TIMEOUT);
        } finally {
            sessions.close();
        }
    }

    @Test
    void rejectsSchemaAndHandlerBoundaryViolationsAndPreservesUnknownRunStack() {
        ObjectMapper mapper = new ObjectMapper();
        BrowserSessionRegistry sessions = new BrowserSessionRegistry(TestBrowser::new);
        try (DefaultToolRegistry tools = new DefaultToolRegistry()) {
            tools.registerAll(BrowserToolDefinitions.definitions(sessions, mapper, TIMEOUT));

            ToolResult extra = execute(tools, mapper, "extra", "browser.click",
                    mapper.createObjectNode().put("selector", "#submit").put("x", true));
            ToolResult selector = execute(tools, mapper, "selector", "browser.click",
                    mapper.createObjectNode().put("selector", "x".repeat(2_049)));
            ToolResult delta = execute(tools, mapper, "delta", "browser.scroll",
                    mapper.createObjectNode().put("deltaY",
                            BrowserToolDefinitions.MAX_SCROLL_DELTA + 1));
            ToolResult url = execute(tools, mapper, "url", "browser.navigate",
                    mapper.createObjectNode().put("url", "file:///tmp/page.html"));
            ToolResult unknownRun = execute(tools, mapper, "unknown", "browser.evidence",
                    mapper.createObjectNode().put("selector", "page"));

            assertThat(extra.status()).isEqualTo(ToolResultStatus.DENIED);
            assertThat(extra.errorStack()).contains("参数字段未声明");
            assertThat(selector.status()).isEqualTo(ToolResultStatus.DENIED);
            assertThat(selector.errorStack()).contains("maxLength");
            assertThat(delta.status()).isEqualTo(ToolResultStatus.DENIED);
            assertThat(delta.errorStack()).contains("maximum");
            assertThat(url.status()).isEqualTo(ToolResultStatus.FAILED);
            assertThat(url.errorStack())
                    .contains("java.lang.IllegalArgumentException")
                    .contains("http")
                    .contains("at ");
            assertThat(unknownRun.status()).isEqualTo(ToolResultStatus.FAILED);
            assertThat(unknownRun.errorStack())
                    .contains("java.lang.IllegalStateException")
                    .contains(RUN_ID.toString())
                    .contains("at ");
        } finally {
            sessions.close();
        }
    }

    @Test
    void rejectsEvidencePayloadOverConfiguredSizeBudget() {
        ObjectMapper mapper = new ObjectMapper();
        TestBrowser browser = new TestBrowser();
        browser.largeScreenshot = true;
        BrowserSessionRegistry sessions = new BrowserSessionRegistry(() -> browser);
        sessions.open(RUN_ID);
        try (DefaultToolRegistry tools = new DefaultToolRegistry()) {
            tools.registerAll(BrowserToolDefinitions.definitions(sessions, mapper, TIMEOUT));

            ToolResult result = execute(tools, mapper, "oversized", "browser.evidence",
                    mapper.createObjectNode().put("selector", "page"));

            assertThat(result.status()).isEqualTo(ToolResultStatus.FAILED);
            assertThat(result.errorStack())
                    .contains("截图超过证据大小上限")
                    .contains("at ");
        } finally {
            sessions.close();
        }
    }

    private ToolResult execute(
            DefaultToolRegistry tools,
            ObjectMapper mapper,
            String callId,
            String name,
            com.fasterxml.jackson.databind.node.ObjectNode arguments) {
        return tools.execute(new ToolCall(callId, name, arguments), new ToolInvocationContext(
                RUN_ID,
                "gui",
                "user",
                workspace,
                Set.of(RequiredCapability.BROWSER),
                true));
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static final class TestBrowser implements BrowserAutomation {

        private final java.util.ArrayList<Duration> timeouts = new java.util.ArrayList<>();
        private URI navigatedUrl;
        private String clickedSelector;
        private String filledSelector;
        private String filledValue;
        private int deltaY;
        private BrowserEvidenceSelector evidenceSelector;
        private boolean largeScreenshot;

        @Override
        public CompletableFuture<NavigationResult> navigate(URI url, Duration timeout) {
            navigatedUrl = url;
            timeouts.add(timeout);
            return CompletableFuture.completedFuture(new NavigationResult(
                    url,
                    URI.create("https://example.test/final"),
                    OptionalInt.of(204)));
        }

        @Override
        public CompletableFuture<Void> click(String selector, Duration timeout) {
            clickedSelector = selector;
            timeouts.add(timeout);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> fill(
                String selector,
                String value,
                Duration timeout) {
            filledSelector = selector;
            filledValue = value;
            timeouts.add(timeout);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> scroll(int deltaY, Duration timeout) {
            this.deltaY = deltaY;
            timeouts.add(timeout);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<BrowserEvidence> capture(
                BrowserEvidenceSelector selector,
                Duration timeout) {
            evidenceSelector = selector;
            timeouts.add(timeout);
            return CompletableFuture.completedFuture(new BrowserEvidence(
                    URI.create("https://example.test/final"),
                    selector.selector(),
                    "<div>ready</div>",
                    "ready",
                    new BrowserScreenshot(
                            largeScreenshot ? new byte[BrowserToolDefinitions.MAX_EVIDENCE_SCREENSHOT_BYTES + 1]
                                    : new byte[] {1, 2, 3},
                            "image/png")));
        }

        @Override
        public CompletableFuture<String> extractDom() {
            return CompletableFuture.completedFuture("<html></html>");
        }

        @Override
        public CompletableFuture<String> extractDom(Duration timeout) {
            return CompletableFuture.completedFuture("<html></html>");
        }

        @Override
        public CompletableFuture<BrowserScreenshot> screenshot(Duration timeout) {
            return CompletableFuture.completedFuture(
                    new BrowserScreenshot(new byte[] {1}, "image/png"));
        }

        @Override
        public void close() {
        }
    }
}
