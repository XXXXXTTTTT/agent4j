package com.agent.web.config;

import com.agent.core.engine.AgentState;
import com.agent.core.gui.BrowserSessionRegistry;
import com.agent.core.intent.TaskKind;
import com.agent.core.nodes.PlannerNode;
import com.agent.core.tool.ToolAuditEvent;
import com.agent.core.tool.ToolCall;
import com.agent.core.tool.ToolInvocationContext;
import com.agent.core.tool.ToolRegistry;
import com.agent.core.tool.ToolResultStatus;
import com.agent.core.intent.RequiredCapability;
import com.agent.sandbox.ast.AstService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionGuiAgentIntegrationTest {

    @TempDir
    Path workspace;

    @Test
    void productionRegistryContainsGovernedBrowserToolsAndOneSessionRegistry() throws Exception {
        ProductionGraphConfiguration configuration = new ProductionGraphConfiguration();
        ProductionAgentProperties properties = properties();
        BrowserSessionRegistry sessions = configuration.productionBrowserSessionRegistry();
        ToolRegistry registry = configuration.productionToolRegistry(
                new AstService(), new ObjectMapper(), sessions, properties);
        try (registry; sessions) {
            assertThat(registry.list()).extracting(definition -> definition.name())
                    .contains("code.apply-diff", "browser.navigate", "browser.click",
                            "browser.fill", "browser.scroll", "browser.evidence");
            assertThat(sessions).isNotNull();
            assertThatThrownBy(() -> sessions.require(java.util.UUID.randomUUID()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("浏览器会话");
        }
    }

    @Test
    void routesOnlyBrowserOperationAgentTasksToGui() {
        ProductionGraphConfiguration configuration = new ProductionGraphConfiguration();
        AgentState browser = AgentState.empty()
                .withVariable(PlannerNode.ROUTE_KEY, PlannerNode.AGENT_ROUTE)
                .withVariable(PlannerNode.TASK_KIND_KEY, TaskKind.BROWSER_OPERATION.name());
        AgentState code = AgentState.empty()
                .withVariable(PlannerNode.ROUTE_KEY, PlannerNode.AGENT_ROUTE)
                .withVariable(PlannerNode.TASK_KIND_KEY, TaskKind.CODE_CHANGE.name());
        AgentState chat = AgentState.empty()
                .withVariable(PlannerNode.ROUTE_KEY, PlannerNode.CHAT_ROUTE);

        assertThat(configuration.plannerGraphRoute(browser)).isEqualTo("gui");
        assertThat(configuration.plannerGraphRoute(code)).isEqualTo("coder");
        assertThat(configuration.plannerGraphRoute(chat)).isEqualTo(PlannerNode.CHAT_ROUTE);
    }

    @Test
    void productionRegistryEmitsCompleteToolAuditEventsThroughInjectedSink() throws Exception {
        ProductionGraphConfiguration configuration = new ProductionGraphConfiguration();
        ProductionAgentProperties properties = properties();
        BrowserSessionRegistry sessions = configuration.productionBrowserSessionRegistry();
        List<ToolAuditEvent> audits = new java.util.concurrent.CopyOnWriteArrayList<>();
        ToolRegistry registry = configuration.productionToolRegistry(
                new AstService(), new ObjectMapper(), sessions, properties, audits::add);
        try (registry; sessions) {
            ToolResultStatus status = registry.execute(
                    new ToolCall("audit-call", "missing.tool", new ObjectMapper().createObjectNode()),
                    new ToolInvocationContext(
                            UUID.randomUUID(), "gui", "user", workspace,
                            Set.of(RequiredCapability.BROWSER), false))
                    .status();

            assertThat(status).isEqualTo(ToolResultStatus.FAILED);
            assertThat(audits).singleElement().satisfies(event -> {
                assertThat(event.toolName()).isEqualTo("missing.tool");
                assertThat(event.status()).isEqualTo(ToolResultStatus.FAILED);
                assertThat(event.argumentsSha256()).hasSize(64);
                assertThat(event.durationMs()).isGreaterThanOrEqualTo(0);
                assertThat(event.errorType()).isEqualTo("ToolNotFoundException");
            });
        }
    }

    private ProductionAgentProperties properties() throws Exception {
        Files.createDirectories(workspace);
        Path bash = Files.createFile(workspace.resolve("bash.exe"));
        return new ProductionAgentProperties(
                true,
                workspace,
                "repository",
                "user",
                "https://page.test/start",
                "PTY",
                bash.toString(),
                "python:3.12-slim",
                "/workspace",
                "",
                "",
                Duration.ofSeconds(30),
                Duration.ofSeconds(15),
                50,
                32_000,
                2,
                12,
                1_800_000,
                120_000,
                200_000,
                3,
                12_000);
    }
}
