package com.agent.web.config;

import com.agent.core.engine.GraphFactory;
import com.agent.core.engine.StateGraph;
import com.agent.core.llm.ModelRouter;
import com.agent.core.memory.MemoryContext;
import com.agent.core.memory.MemoryContextProvider;
import com.agent.core.nodes.CoderNode;
import com.agent.core.nodes.OpsNode;
import com.agent.core.nodes.PlannerNode;
import com.agent.core.nodes.ReviewerNode;
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

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** 装配真实模型、代码工具、沙箱和浏览器驱动的生产 Agent 图。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
@ConditionalOnBean(ModelRouter.class)
@EnableConfigurationProperties(ProductionAgentProperties.class)
public class ProductionGraphConfiguration {

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
            ObjectMapper objectMapper) {
        Objects.requireNonNull(properties, "properties 不能为空");
        Objects.requireNonNull(modelRouter, "modelRouter 不能为空");
        Objects.requireNonNull(memoryContextProvider, "memoryContextProvider 不能为空");
        Objects.requireNonNull(terminalService, "terminalService 不能为空");
        Objects.requireNonNull(browserAutomation, "browserAutomation 不能为空");
        Objects.requireNonNull(astService, "astService 不能为空");
        Objects.requireNonNull(snapshotService, "snapshotService 不能为空");
        Objects.requireNonNull(logPublisher, "logPublisher 不能为空");
        Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
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
                new ObjectMapper());
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
            TerminalTarget target) {
        PlannerNode planner = new PlannerNode(modelRouter, memoryContextProvider, 5);
        CoderNode coder = new CoderNode(astService, modelRouter, objectMapper, snapshotService);
        OpsNode ops = new OpsNode(
                terminalService, target, properties.commandTimeout(), logPublisher);
        ReviewerNode reviewer = new ReviewerNode(
                browserAutomation, modelRouter, objectMapper, properties.browserTimeout());
        return new StateGraph(properties.maxSteps())
                .addNode("planner", planner)
                .addNode("coder", coder)
                .addNode("ops", ops)
                .addNode("reviewer", reviewer)
                .setEntryPoint("planner")
                .addEdge("planner", "coder")
                .addEdge("coder", "ops")
                .addEdge("ops", "reviewer")
                .addConditionalEdges(
                        "reviewer",
                        state -> shouldRepair(state, properties.maxRepairAttempts())
                                ? "repair"
                                : "finish",
                        Map.of("repair", "coder", "finish", StateGraph.END));
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
