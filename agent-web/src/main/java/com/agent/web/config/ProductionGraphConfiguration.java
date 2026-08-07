package com.agent.web.config;

import com.agent.core.engine.GraphFactory;
import com.agent.core.engine.InterruptPolicy;
import com.agent.core.engine.StateGraph;
import com.agent.core.harness.HarnessHookChain;
import com.agent.core.llm.ModelRouter;
import com.agent.core.memory.MemoryContext;
import com.agent.core.memory.MemoryContextProvider;
import com.agent.core.nodes.CoderNode;
import com.agent.core.nodes.OpsNode;
import com.agent.core.nodes.PlannerNode;
import com.agent.core.nodes.PlannerPromptTemplates;
import com.agent.core.nodes.ReviewerNode;
import com.agent.core.intent.ModelIntentClassifier;
import com.agent.core.intent.ModelRouterIntentModel;
import com.agent.core.trace.RunLogPublisher;
import com.agent.sandbox.ast.AstService;
import com.agent.sandbox.ast.WorkspaceSnapshotService;
import com.agent.sandbox.browser.BrowserAutomation;
import com.agent.sandbox.browser.PlaywrightBrowserService;
import com.agent.sandbox.pty.DockerTarget;
import com.agent.sandbox.pty.PtyTarget;
import com.agent.sandbox.pty.SandboxTerminalService;
import com.agent.sandbox.pty.TerminalTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.List;

/** 装配真实模型、代码工具、沙箱和浏览器驱动的生产 Agent 图。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
@ConditionalOnBean(ModelRouter.class)
@EnableConfigurationProperties(ProductionAgentProperties.class)
public class ProductionGraphConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            ProductionGraphConfiguration.class);

    /** 创建 JavaParser/JGit 服务。 */
    @Bean
    AstService productionAstService() {
        return new AstService();
    }

    /** 创建受限工作区快照服务。 */
    @Bean
    WorkspaceSnapshotService productionWorkspaceSnapshotService(
            ProductionAgentProperties properties) {
        return new WorkspaceSnapshotService(
                properties.snapshotMaxFiles(), properties.snapshotMaxBytes());
    }

    /** 创建统一 Docker/PTY 异步终端服务。 */
    @Bean(destroyMethod = "close")
    SandboxTerminalService productionTerminalService() {
        return new SandboxTerminalService();
    }

    /** 创建保持 Playwright 线程亲和性的浏览器服务。 */
    @Bean(destroyMethod = "close")
    BrowserAutomation productionBrowserAutomation() {
        return new PlaywrightBrowserService();
    }

    /** 没有启用长期记忆实现时提供空上下文。 */
    @Bean
    @ConditionalOnMissingBean(MemoryContextProvider.class)
    MemoryContextProvider emptyMemoryContextProvider() {
        return request -> new MemoryContext("", 0);
    }

    /** 创建写入现有日志归档的默认 Harness Hook 链。 */
    @Bean
    @ConditionalOnMissingBean(HarnessHookChain.class)
    HarnessHookChain productionHarnessHookChain() {
        return new HarnessHookChain(
                List.of(event -> LOGGER.info(
                        "Harness event runId={} nodeName={} eventType={} metadata={}",
                        event.runId(),
                        event.nodeName(),
                        event.eventType(),
                        event.metadata())),
                failure -> LOGGER.warn(
                        "Harness hook failure hookName={} eventType={}",
                        failure.hookName(),
                        failure.eventType(),
                        failure));
    }

    /** 注册精确图标识 `code-agent` 的生产执行链。 */
    @Bean("code-agent")
    GraphFactory codeAgentGraph(
            ProductionAgentProperties properties,
            ModelRouter modelRouter,
            MemoryContextProvider memoryContextProvider,
            SandboxTerminalService terminalService,
            BrowserAutomation browserAutomation,
            AstService astService,
            WorkspaceSnapshotService snapshotService,
            RunLogPublisher logPublisher,
            ObjectMapper objectMapper,
            HarnessHookChain harness) {
        Objects.requireNonNull(properties, "properties 不能为空");
        Objects.requireNonNull(modelRouter, "modelRouter 不能为空");
        Objects.requireNonNull(memoryContextProvider, "memoryContextProvider 不能为空");
        Objects.requireNonNull(terminalService, "terminalService 不能为空");
        Objects.requireNonNull(browserAutomation, "browserAutomation 不能为空");
        Objects.requireNonNull(astService, "astService 不能为空");
        Objects.requireNonNull(snapshotService, "snapshotService 不能为空");
        Objects.requireNonNull(logPublisher, "logPublisher 不能为空");
        Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        Objects.requireNonNull(harness, "harness 不能为空");
        TerminalTarget target = terminalTarget(properties);
        return () -> createGraph(
                properties,
                modelRouter,
                memoryContextProvider,
                terminalService,
                browserAutomation,
                astService,
                snapshotService,
                logPublisher,
                objectMapper,
                harness,
                target);
    }

    GraphFactory codeAgentGraph(
            ProductionAgentProperties properties,
            ModelRouter modelRouter,
            MemoryContextProvider memoryContextProvider,
            SandboxTerminalService terminalService,
            BrowserAutomation browserAutomation,
            AstService astService,
            WorkspaceSnapshotService snapshotService,
            RunLogPublisher logPublisher) {
        return codeAgentGraph(
                properties,
                modelRouter,
                memoryContextProvider,
                terminalService,
                browserAutomation,
                astService,
                snapshotService,
                logPublisher,
                new ObjectMapper(),
                HarnessHookChain.noop());
    }

    GraphFactory codeAgentGraph(
            ProductionAgentProperties properties,
            ModelRouter modelRouter,
            MemoryContextProvider memoryContextProvider,
            SandboxTerminalService terminalService,
            BrowserAutomation browserAutomation,
            AstService astService,
            WorkspaceSnapshotService snapshotService,
            RunLogPublisher logPublisher,
            ObjectMapper objectMapper) {
        return codeAgentGraph(
                properties,
                modelRouter,
                memoryContextProvider,
                terminalService,
                browserAutomation,
                astService,
                snapshotService,
                logPublisher,
                objectMapper,
                HarnessHookChain.noop());
    }

    private StateGraph createGraph(
            ProductionAgentProperties properties,
            ModelRouter modelRouter,
            MemoryContextProvider memoryContextProvider,
            SandboxTerminalService terminalService,
            BrowserAutomation browserAutomation,
            AstService astService,
            WorkspaceSnapshotService snapshotService,
            RunLogPublisher logPublisher,
            ObjectMapper objectMapper,
            HarnessHookChain harness,
            TerminalTarget target) {
        var promptCatalog = PlannerPromptTemplates.catalog();
        PlannerNode planner = new PlannerNode(
                modelRouter,
                memoryContextProvider,
                5,
                promptCatalog,
                PlannerNode.defaultContextWindowManager(),
                new ModelIntentClassifier(
                        new ModelRouterIntentModel(modelRouter),
                        objectMapper,
                        promptCatalog),
                properties.plannerContextMaxTokens());
        CoderNode coder = new CoderNode(astService, modelRouter, objectMapper, snapshotService);
        OpsNode ops = new OpsNode(
                terminalService, target, properties.commandTimeout(), logPublisher);
        ReviewerNode reviewer = new ReviewerNode(
                browserAutomation, modelRouter, objectMapper, properties.browserTimeout());
        return new StateGraph(
                properties.executionBudget(), InterruptPolicy.never(), harness)
                .addNode("planner", planner)
                .addNode("coder", coder)
                .addNode("ops", ops)
                .addNode("reviewer", reviewer)
                .setEntryPoint("planner")
                .addConditionalEdges(
                        "planner",
                        state -> plannerRoute(state),
                        Map.of(
                                PlannerNode.CHAT_ROUTE, StateGraph.END,
                                PlannerNode.AGENT_ROUTE, "coder",
                                PlannerNode.FAILED_ROUTE, StateGraph.END))
                .addConditionalEdges(
                        "coder",
                        state -> state.variables().containsKey(CoderNode.ERROR_KEY)
                                ? PlannerNode.FAILED_ROUTE
                                : "continue",
                        Map.of(PlannerNode.FAILED_ROUTE, StateGraph.END, "continue", "ops"))
                .addEdge("ops", "reviewer")
                .addConditionalEdges(
                        "reviewer",
                        state -> shouldRepair(state, properties.maxRepairAttempts())
                                ? "repair"
                                : "finish",
                        Map.of("repair", "coder", "finish", StateGraph.END));
    }

    private String plannerRoute(com.agent.core.engine.AgentState state) {
        if (state.variables().containsKey(PlannerNode.ERROR_KEY)) {
            return PlannerNode.FAILED_ROUTE;
        }
        return PlannerNode.CHAT_ROUTE.equals(state.variables().get(PlannerNode.ROUTE_KEY))
                ? PlannerNode.CHAT_ROUTE
                : PlannerNode.AGENT_ROUTE;
    }

    private boolean shouldRepair(
            com.agent.core.engine.AgentState state,
            int maxRepairAttempts) {
        if (!"false".equals(state.variables().get(ReviewerNode.APPROVED_KEY))) {
            return false;
        }
        String attempts = state.variables().get(CoderNode.ATTEMPT_KEY);
        if (attempts == null) {
            return false;
        }
        return Integer.parseInt(attempts) < maxRepairAttempts;
    }

    TerminalTarget terminalTarget(ProductionAgentProperties properties) {
        return switch (properties.executionMode()) {
            case "DOCKER" -> properties.workspaceSourceContainer().isBlank()
                    ? new DockerTarget(
                            properties.dockerImage(),
                            properties.workspace(),
                            properties.containerWorkspace())
                    : new DockerTarget(
                            properties.dockerImage(),
                            properties.workspace(),
                            properties.containerWorkspace(),
                            new DockerTarget.ContainerWorkspaceSource(
                                    properties.workspaceSourceContainer(),
                                    properties.workspaceSourcePath()));
            case "PTY" -> new PtyTarget(
                    Path.of(properties.bashExecutable()),
                    properties.workspace());
            default -> throw new IllegalArgumentException(
                    "executionMode 必须精确为 DOCKER 或 PTY");
        };
    }
}
