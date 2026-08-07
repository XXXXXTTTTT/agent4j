package com.agent.web;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.GraphFactory;
import com.agent.core.engine.InterruptPolicy;
import com.agent.core.engine.InterruptRequest;
import com.agent.core.engine.Node;
import com.agent.core.engine.NodeExecutionContext;
import com.agent.core.engine.StateGraph;
import com.agent.core.nodes.CoderNode;
import com.agent.core.nodes.OpsNode;
import com.agent.core.nodes.ReviewerNode;
import com.agent.core.trace.RunLogEvent;
import com.agent.core.trace.RunLogPublisher;
import com.agent.core.trace.RunLogStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.ViewportSize;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class ProductWorkbenchBrowserTest {

    private static final String GRAPH_ID = "code-agent";
    private static final String ANSI_LOG = "\u001b[32mtests passed\u001b[0m\r\n";
    private static final String UNIFIED_DIFF = """
            diff --git a/src/App.java b/src/App.java
            index 3367afd..d68a283 100644
            --- a/src/App.java
            +++ b/src/App.java
            @@ -1,3 +1,3 @@
             class App {
            -    String status = "old";
            +    String status = "ready";
             }
            """;
    private static final String SCREENSHOT_DATA_URL = createEvidencePng();
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void requireExternalServices() {
        boolean dockerAvailable;
        try {
            dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException exception) {
            Assumptions.assumeTrue(false, "Docker Engine 不可用: " + exception.getMessage());
            return;
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker Engine 不可用");
        requireLaunchableChromium();
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @Test
    void operatesWorkbenchAcrossDesktopAndMobileWithRealBrowser() throws Exception {
        LayoutReference desktop = readReference("desktop-reference.json");
        LayoutReference mobile = readReference("mobile-reference.json");
        ReactiveWebServerApplicationContext application = startApplication();
        Path screenshotDirectory = Path.of("target", "workbench");
        Files.createDirectories(screenshotDirectory);

        try (Playwright playwright = Playwright.create();
                Browser browser = playwright.chromium().launch(
                        new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = browser.newPage(new Browser.NewPageOptions()
                    .setViewportSize(desktop.width(), desktop.height()));
            List<String> pageErrors = new CopyOnWriteArrayList<>();
            List<String> consoleErrors = new CopyOnWriteArrayList<>();
            page.onPageError(pageErrors::add);
            page.onConsoleMessage(message -> {
                if ("error".equals(message.type())) {
                    consoleErrors.add(message.text());
                }
            });
            page.navigate("http://127.0.0.1:"
                    + application.getWebServer().getPort() + "/");

            page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("新建会话")).click();
            page.getByRole(com.microsoft.playwright.options.AriaRole.TEXTBOX,
                    new Page.GetByRoleOptions().setName("发送消息"))
                    .fill("验证工作台审批与执行证据");
            page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("发送消息")).click();
            try {
                page.getByTestId("approval-dialog").waitFor();
            } catch (PlaywrightException exception) {
                Object diagnostics = page.evaluate("""
                        () => ({
                          body: document.body.innerText,
                          location: window.location.href,
                          resources: performance.getEntriesByType('resource')
                            .map(entry => entry.name)
                        })
                        """);
                throw new AssertionError(
                        "等待审批失败，页面状态: " + diagnostics
                                + ", pageErrors=" + pageErrors
                                + ", consoleErrors=" + consoleErrors,
                        exception);
            }
            page.waitForFunction("""
                    () => document.querySelector('[data-testid="trace-timeline"]')
                        ?.textContent?.includes('PTY 已连接')
                    """);

            assertThat(page.getByTestId("run-status").textContent())
                    .contains("WAITING_APPROVAL");
            assertThat(page.getByTestId("code-panel").textContent())
                    .contains("src/App.java");
            assertRequiredRegions(page, desktop);
            assertNoOverlap(
                    page.getByTestId("workspace-main"),
                    page.getByLabel("执行检查器"));

            page.getByRole(com.microsoft.playwright.options.AriaRole.TAB,
                    new Page.GetByRoleOptions().setName("终端")).click();
            page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("修改")).click();
            page.getByLabel("ops.command").fill("mvn verify");
            page.getByLabel("审批说明").fill("已核对浏览器测试命令");
            page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("批准修改")).click();

            page.waitForFunction("""
                    () => document.querySelector('[data-testid="run-status"]')
                        ?.textContent?.includes('COMPLETED')
                    """);
            page.waitForFunction("""
                    () => document.querySelector('[data-testid="terminal-panel"]')
                        ?.textContent?.includes('tests passed')
                    """);
            assertThat(page.getByTestId("terminal-panel").textContent())
                    .contains("tests passed");

            page.getByRole(com.microsoft.playwright.options.AriaRole.TAB,
                    new Page.GetByRoleOptions().setName("浏览器")).click();
            Locator reviewPanel = page.getByTestId("review-panel");
            reviewPanel.waitFor();
            Object evidenceState = reviewPanel.evaluate("""
                    panel => ({
                      text: panel.innerText,
                      images: [...panel.querySelectorAll('img')].map(image => ({
                        alt: image.alt,
                        naturalWidth: image.naturalWidth,
                        naturalHeight: image.naturalHeight
                      }))
                    })
                    """);
            assertThat(reviewPanel.locator("img").count())
                    .as("审查面板状态: %s", evidenceState)
                    .isEqualTo(1);
            Locator evidenceImage = page.getByRole(
                    com.microsoft.playwright.options.AriaRole.IMG,
                    new Page.GetByRoleOptions().setName("审查截图").setExact(false));
            evidenceImage.waitFor();
            assertThat(evidenceImage.evaluate("image => image.naturalWidth")).isEqualTo(320);
            page.getByRole(com.microsoft.playwright.options.AriaRole.TAB,
                    new Page.GetByRoleOptions().setName("DOM")).click();
            try {
                page.waitForFunction("""
                        () => [...document.querySelectorAll(
                            '[data-testid="review-panel"] .view-line')]
                            .some(line => line.textContent
                              ?.replaceAll(String.fromCharCode(160), ' ')
                              .includes('Workbench evidence'))
                        """);
            } catch (PlaywrightException exception) {
                Object domState = reviewPanel.evaluate("""
                        panel => ({
                          text: panel.innerText,
                          viewLines: [...panel.querySelectorAll('.view-line')]
                            .map(line => line.textContent),
                          editorCount: panel.querySelectorAll('.monaco-editor').length
                        })
                        """);
                throw new AssertionError(
                        "等待 DOM 证据失败，面板状态: " + domState
                                + ", pageErrors=" + pageErrors
                                + ", consoleErrors=" + consoleErrors,
                        exception);
            }
            String renderedDom = (String) reviewPanel.locator(".view-line").first()
                    .evaluate("line => line.textContent"
                            + ".replaceAll(String.fromCharCode(160), ' ')");
            assertThat(renderedDom)
                    .contains("Workbench evidence");
            assertThat(pageErrors).isEmpty();
            assertThat(page.getByTestId("trace-timeline").textContent())
                    .contains("完成");

            String conversationUrl = page.url();
            assertThat(conversationUrl).contains("conversationId=");
            page.reload();
            page.getByTestId("workspace-main")
                    .getByText("验证工作台审批与执行证据").waitFor();
            page.getByTestId("workspace-main").getByText("无需修改").waitFor();
            assertThat(page.url()).isEqualTo(conversationUrl);

            Path desktopScreenshot = screenshotDirectory.resolve("desktop.png");
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(desktopScreenshot)
                    .setFullPage(true));
            assertNonBlankPng(desktopScreenshot);

            page.setViewportSize(mobile.width(), mobile.height());
            assertRequiredRegions(page, mobile);
            assertThat(page.evaluate("() => document.documentElement.scrollWidth"))
                    .isEqualTo(mobile.width());
            assertButtonsContainText(page);
            Path mobileScreenshot = screenshotDirectory.resolve("mobile.png");
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(mobileScreenshot)
                    .setFullPage(true));
            assertNonBlankPng(mobileScreenshot);
            page.close();
        } finally {
            application.close();
        }
    }

    private ReactiveWebServerApplicationContext startApplication() {
        SpringApplication application = new SpringApplication(
                AgentWebApplication.class,
                BrowserFlowConfiguration.class);
        application.setRegisterShutdownHook(false);
        return (ReactiveWebServerApplicationContext) application.run(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--agent.production.enabled=true",
                "--agent.production.workspace=" + Path.of(".").toAbsolutePath().normalize(),
                "--agent.production.repository-id=browser-test",
                "--agent.production.user-id=browser-user");
    }

    private LayoutReference readReference(String fileName) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(
                "/workbench/" + fileName)) {
            if (input == null) {
                throw new IOException("找不到工作台参考文件: " + fileName);
            }
            return new ObjectMapper().readValue(input, LayoutReference.class);
        }
    }

    private void assertRequiredRegions(Page page, LayoutReference reference) {
        for (String testId : reference.requiredTestIds()) {
            Locator region = page.getByTestId(testId);
            assertThat(region.isVisible()).as(testId).isTrue();
            BoundingBox box = region.boundingBox();
            assertThat(box).as(testId).isNotNull();
            assertThat(box.width).as(testId + " width").isGreaterThan(0);
            assertThat(box.height).as(testId + " height").isGreaterThan(0);
        }
    }

    private void assertNoOverlap(Locator left, Locator right) {
        BoundingBox leftBox = left.boundingBox();
        BoundingBox rightBox = right.boundingBox();
        assertThat(leftBox).isNotNull();
        assertThat(rightBox).isNotNull();
        assertThat(leftBox.x + leftBox.width).isLessThanOrEqualTo(rightBox.x);
    }

    private void assertButtonsContainText(Page page) {
        Object overflowCount = page.evaluate("""
                () => [...document.querySelectorAll('button')]
                    .filter(button => button.scrollWidth > button.clientWidth + 1).length
                """);
        assertThat(overflowCount).isEqualTo(0);
    }

    private void assertNonBlankPng(Path screenshot) throws IOException {
        BufferedImage image = ImageIO.read(screenshot.toFile());
        assertThat(image).isNotNull();
        int first = image.getRGB(0, 0);
        boolean different = false;
        for (int y = 0; y < image.getHeight() && !different; y += 20) {
            for (int x = 0; x < image.getWidth(); x += 20) {
                if (image.getRGB(x, y) != first) {
                    different = true;
                    break;
                }
            }
        }
        assertThat(different).isTrue();
    }

    private static void requireLaunchableChromium() {
        try (Playwright playwright = Playwright.create();
                Browser browser = playwright.chromium().launch(
                        new BrowserType.LaunchOptions().setHeadless(true))) {
            assertThat(browser.isConnected()).isTrue();
        } catch (PlaywrightException exception) {
            Assumptions.assumeTrue(
                    false,
                    "当前环境无法启动 Playwright Chromium: " + exception.getMessage());
        }
    }

    private static String createEvidencePng() {
        BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(236, 242, 239));
            graphics.fillRect(0, 0, 320, 180);
            graphics.setColor(new Color(25, 120, 93));
            graphics.fillRect(0, 0, 320, 42);
            graphics.setColor(new Color(23, 32, 30));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
            graphics.drawString("Workbench evidence", 24, 96);
            graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            graphics.drawString("DOM + screenshot captured", 24, 124);
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return "data:image/png;base64,"
                    + Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("生成浏览器测试证据 PNG 失败", exception);
        }
    }

    record LayoutReference(int width, int height, java.util.List<String> requiredTestIds) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BrowserFlowConfiguration {

        @Bean(GRAPH_ID)
        GraphFactory browserFlow(RunLogPublisher logPublisher) {
            return () -> {
                InterruptPolicy policy = (runId, nodeName, state) ->
                        "ops".equals(nodeName)
                                ? Optional.of(new InterruptRequest(
                                        UUID.fromString(
                                                "cb93865d-795a-4942-886a-a523c14bdb85"),
                                        "ops",
                                        "浏览器测试命令需要审批",
                                        Map.of(
                                                OpsNode.COMMAND_KEY,
                                                state.variables().get(OpsNode.COMMAND_KEY))))
                                : Optional.empty();
                return new StateGraph(2, policy)
                        .addNode("prepare", state -> state
                                .withVariable(OpsNode.COMMAND_KEY, "mvn test")
                                .withVariable(CoderNode.UNIFIED_DIFF_KEY, UNIFIED_DIFF)
                                .withVariable(
                                        ReviewerNode.SCREENSHOT_DATA_URL_KEY,
                                        SCREENSHOT_DATA_URL)
                                .withVariable(
                                        ReviewerNode.DOM_KEY,
                                        "<main>Workbench evidence</main>")
                                .withVariable(
                                        ReviewerNode.FINAL_URL_KEY,
                                        "https://example.test/workbench")
                                .withVariable(ReviewerNode.SUMMARY_KEY, "界面证据完整")
                                .withVariable(ReviewerNode.FEEDBACK_KEY, "无需修改")
                                .withVariable(ReviewerNode.MODEL_KEY, "vision-test")
                                .withTraceEntry("prepare"))
                        .addNode("ops", new Node() {
                            @Override
                            public AgentState execute(AgentState state) {
                                throw new AssertionError("不应调用无上下文入口");
                            }

                            @Override
                            public AgentState execute(
                                    NodeExecutionContext context,
                                    AgentState state) {
                                logPublisher.publish(new RunLogEvent(
                                        UUID.randomUUID(),
                                        context.runId(),
                                        context.nodeName(),
                                        0,
                                        RunLogStream.PTY,
                                        ANSI_LOG,
                                        Instant.now()));
                                return state
                                        .withVariable(OpsNode.STDOUT_KEY, ANSI_LOG)
                                        .withVariable(OpsNode.STDERR_KEY, "")
                                        .withVariable(OpsNode.EXIT_CODE_KEY, "0")
                                        .withVariable(OpsNode.TIMED_OUT_KEY, "false")
                                        .withTraceEntry("ops");
                            }
                        })
                        .setEntryPoint("prepare")
                        .addEdge("prepare", "ops")
                        .addEdge("ops", StateGraph.END);
            };
        }
    }
}
