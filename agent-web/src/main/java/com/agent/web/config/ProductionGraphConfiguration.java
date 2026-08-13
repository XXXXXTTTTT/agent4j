package com.agent.web.config;

import com.agent.core.engine.GraphFactory;
import com.agent.core.engine.StateGraph;
import com.agent.core.gui.BrowserSessionRegistry;
import com.agent.core.harness.HarnessHookChain;
import com.agent.core.cli.CliApprovalInterruptPolicy;
import com.agent.core.cli.CliCommandCatalog;
import com.agent.core.cli.CliCommandDefinition;
import com.agent.core.cli.CliRiskLevel;
import com.agent.core.cli.WorkspaceTerminalTargetResolver;
import com.agent.core.intent.RequiredCapability;
import com.agent.core.intent.TaskKind;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.ImageGenerationClient;
import com.agent.core.llm.TaskType;
import com.agent.core.profile.AgentProfile;
import com.agent.core.knowledge.KnowledgeContextProvider;
import com.agent.core.memory.MemoryContext;
import com.agent.core.memory.MemoryContextProvider;
import com.agent.core.nodes.CoderNode;
import com.agent.core.nodes.GuiAgentNode;
import com.agent.core.nodes.OpsNode;
import com.agent.core.nodes.PlannerNode;
import com.agent.core.nodes.PlannerPromptTemplates;
import com.agent.core.nodes.ReviewerNode;
import com.agent.core.nodes.ToolAgentNode;
import com.agent.core.security.DefaultOutputRedactor;
import com.agent.core.security.DefaultPromptInjectionDetector;
import com.agent.core.security.DefaultToolParameterPolicy;
import com.agent.core.security.SecurityViolationSink;
import com.agent.core.intent.ModelIntentClassifier;
import com.agent.core.intent.ModelRouterIntentModel;
import com.agent.core.trace.RunLogPublisher;
import com.agent.core.tool.DefaultToolAuthorizer;
import com.agent.core.tool.DefaultToolRegistry;
import com.agent.core.tool.JacksonToolSchemaValidator;
import com.agent.core.tool.ToolAuditEvent;
import com.agent.core.tool.ToolAuditSink;
import com.agent.core.tool.ToolRegistry;
import com.agent.core.tool.builtin.CodePatchTool;
import com.agent.core.tool.builtin.BrowserToolDefinitions;
import com.agent.core.tool.builtin.ImageGenerationTool;
import com.agent.core.skill.SkillCatalog;
import com.agent.core.skill.SkillDefinition;
import com.agent.sandbox.ast.AstService;
import com.agent.sandbox.ast.WorkspaceSnapshotService;
import com.agent.sandbox.browser.BrowserAutomation;
import com.agent.sandbox.browser.PlaywrightBrowserService;
import com.agent.sandbox.pty.DockerTarget;
import com.agent.sandbox.pty.PtyTarget;
import com.agent.sandbox.pty.SandboxTerminalService;
import com.agent.sandbox.pty.TerminalTarget;
import com.agent.web.security.JdbcSecurityViolationSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.List;
import java.util.Set;

/** 装配真实模型、代码工具、沙箱和浏览器驱动的生产 Agent 图。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
@ConditionalOnBean(ModelRouter.class)
@EnableConfigurationProperties(ProductionAgentProperties.class)
public class ProductionGraphConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            ProductionGraphConfiguration.class);
    private static final String GUI_ROUTE = "gui";
    private static final String CODER_ROUTE = "coder";
    private static final String REPAIR_ROUTE = "repair";
    private static final String FINISH_ROUTE = "finish";
    private static final String FAILURE_ROUTE = "failure";
    private static final String TOOL_ROUTE = "tool";
    private static final String REVIEWER_FAILURE_NODE = "reviewer-failure";

    /** 创建 JavaParser/JGit 服务。 */
    @Bean
    AstService productionAstService() {
        return new AstService();
    }

    /** 注册生产 Coder 允许调用的内置补丁工具。 */
    @Bean(destroyMethod = "close")
    ToolRegistry productionToolRegistry(
            AstService astService,
            ObjectMapper objectMapper,
            BrowserSessionRegistry browserSessions,
            ProductionAgentProperties properties,
            ToolAuditSink auditSink,
            SecurityViolationSink securityViolationSink,
            ImageGenerationClient imageGenerationClient) {
        DefaultToolRegistry registry = new DefaultToolRegistry(
                new JacksonToolSchemaValidator(),
                new DefaultToolAuthorizer(),
                auditSink,
                objectMapper,
                System::nanoTime,
                new DefaultToolParameterPolicy(Map.of()),
                new DefaultOutputRedactor(),
                securityViolationSink);
        registry.register(CodePatchTool.definition(astService, objectMapper));
        registry.registerAll(BrowserToolDefinitions.definitions(
                browserSessions, objectMapper, properties.browserTimeout()));
        if (imageGenerationClient != null) {
            registry.register(ImageGenerationTool.definition(
                    imageGenerationClient, objectMapper, properties.browserTimeout()));
        }
        return registry;
    }

    ToolRegistry productionToolRegistry(
            AstService astService,
            ObjectMapper objectMapper,
            BrowserSessionRegistry browserSessions,
            ProductionAgentProperties properties,
            ToolAuditSink auditSink,
            SecurityViolationSink securityViolationSink) {
        return productionToolRegistry(
                astService,
                objectMapper,
                browserSessions,
                properties,
                auditSink,
                securityViolationSink,
                null);
    }

    /** 为直接构造生产图的兼容入口提供无外部副作用的审计端口。 */
    ToolRegistry productionToolRegistry(
            AstService astService,
            ObjectMapper objectMapper,
            BrowserSessionRegistry browserSessions,
            ProductionAgentProperties properties,
            ToolAuditSink auditSink) {
        return productionToolRegistry(
                astService,
                objectMapper,
                browserSessions,
                properties,
                auditSink,
                SecurityViolationSink.noop());
    }

    /** 为直接构造生产图的兼容入口提供无外部副作用的安全端口。 */
    ToolRegistry productionToolRegistry(
            AstService astService,
            ObjectMapper objectMapper,
            BrowserSessionRegistry browserSessions,
            ProductionAgentProperties properties) {
        return productionToolRegistry(
                astService,
                objectMapper,
                browserSessions,
                properties,
                ToolAuditSink.noop(),
                SecurityViolationSink.noop());
    }

    /** 将工具审计事件写入现有 Logback 控制台与滚动文件。 */
    @Bean
    ToolAuditSink productionToolAuditSink() {
        return event -> LOGGER.info(
                "Tool audit runId={} nodeName={} userId={} callId={} toolName={} risk={} status={} durationMs={} argumentsSha256={} errorType={} cancellationRequested={}",
                event.runId(),
                event.nodeName(),
                event.userId(),
                event.callId(),
                event.toolName(),
                event.riskLevel().map(Enum::name).orElse(""),
                event.status(),
                event.durationMs(),
                event.argumentsSha256(),
                event.errorType(),
                event.cancellationRequested());
    }

    /** 创建写入 PostgreSQL 的安全违规端口。 */
    @Bean
    SecurityViolationSink productionSecurityViolationSink(
            JdbcClient jdbcClient,
            PlatformTransactionManager transactionManager) {
        return new JdbcSecurityViolationSink(
                jdbcClient,
                new TransactionTemplate(transactionManager));
    }

    /** 创建按 Run 隔离浏览器会话的注册表。 */
    @Bean(destroyMethod = "close")
    BrowserSessionRegistry productionBrowserSessionRegistry() {
        return new BrowserSessionRegistry(PlaywrightBrowserService::new);
    }

    /** 声明模型可以选择的精确只读验证命令。 */
    @Bean
    CliCommandCatalog productionCliCommandCatalog() {
        return new CliCommandCatalog(List.of(
                new CliCommandDefinition(
                        "test.cat",
                        "cat",
                        List.of(),
                        CliRiskLevel.READ_ONLY,
                        Set.of(RequiredCapability.TERMINAL)),
                new CliCommandDefinition(
                        "test.maven",
                        "mvn",
                        List.of("test"),
                        CliRiskLevel.READ_ONLY,
                        Set.of(RequiredCapability.TERMINAL)),
                new CliCommandDefinition(
                        "mvn",
                        "mvn",
                        List.of(),
                        CliRiskLevel.READ_ONLY,
                        Set.of(RequiredCapability.TERMINAL))));
    }

    /** 创建与生产终端目标绑定的 CLI 审批策略。 */
    @Bean
    CliApprovalInterruptPolicy productionCliApprovalInterruptPolicy(
            ProductionAgentProperties properties,
            CliCommandCatalog catalog,
            WorkspaceTerminalTargetResolver workspaceTargetResolver,
            ObjectMapper objectMapper) {
        return new CliApprovalInterruptPolicy(
                catalog,
                workspaceTargetResolver,
                properties.commandTimeout(),
                objectMapper);
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

    /** 没有启用项目知识实现时提供无外部访问的空上下文。 */
    @Bean
    @ConditionalOnMissingBean(KnowledgeContextProvider.class)
    KnowledgeContextProvider emptyKnowledgeContextProvider() {
        return KnowledgeContextProvider.empty();
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
            KnowledgeProperties knowledgeProperties,
            ModelRouter modelRouter,
            MemoryContextProvider memoryContextProvider,
            KnowledgeContextProvider knowledgeContextProvider,
            SandboxTerminalService terminalService,
            BrowserAutomation browserAutomation,
            AstService astService,
            WorkspaceSnapshotService snapshotService,
            RunLogPublisher logPublisher,
            ObjectMapper objectMapper,
            HarnessHookChain harness,
            SecurityViolationSink securityViolationSink,
            ToolRegistry toolRegistry,
            CliCommandCatalog commandCatalog,
            CliApprovalInterruptPolicy approvalPolicy,
            BrowserSessionRegistry browserSessions) {
        Objects.requireNonNull(properties, "properties 不能为空");
        Objects.requireNonNull(knowledgeProperties, "knowledgeProperties 不能为空");
        knowledgeProperties.validate();
        Objects.requireNonNull(modelRouter, "modelRouter 不能为空");
        Objects.requireNonNull(memoryContextProvider, "memoryContextProvider 不能为空");
        Objects.requireNonNull(knowledgeContextProvider, "knowledgeContextProvider 不能为空");
        Objects.requireNonNull(terminalService, "terminalService 不能为空");
        Objects.requireNonNull(browserAutomation, "browserAutomation 不能为空");
        Objects.requireNonNull(astService, "astService 不能为空");
        Objects.requireNonNull(snapshotService, "snapshotService 不能为空");
        Objects.requireNonNull(logPublisher, "logPublisher 不能为空");
        Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        Objects.requireNonNull(harness, "harness 不能为空");
        Objects.requireNonNull(securityViolationSink, "securityViolationSink 不能为空");
        Objects.requireNonNull(toolRegistry, "toolRegistry 不能为空");
        Objects.requireNonNull(commandCatalog, "commandCatalog 不能为空");
        Objects.requireNonNull(approvalPolicy, "approvalPolicy 不能为空");
        Objects.requireNonNull(browserSessions, "browserSessions 不能为空");
        return () -> createGraph(
                properties,
                modelRouter,
                memoryContextProvider,
                knowledgeContextProvider,
                terminalService,
                browserAutomation,
                astService,
                snapshotService,
                logPublisher,
                objectMapper,
                harness,
                securityViolationSink,
                knowledgeProperties.maxTokens(),
                toolRegistry,
                commandCatalog,
                approvalPolicy,
                browserSessions);
    }

    /** 注册只含受治理 Ops 节点的 CLI 专用执行图。 */
    @Bean("governed-cli")
    GraphFactory governedCliGraph(
            ProductionAgentProperties properties,
            SandboxTerminalService terminalService,
            RunLogPublisher logPublisher,
            CliApprovalInterruptPolicy approvalPolicy,
            HarnessHookChain harness) {
        Objects.requireNonNull(properties, "properties 不能为空");
        Objects.requireNonNull(terminalService, "terminalService 不能为空");
        Objects.requireNonNull(logPublisher, "logPublisher 不能为空");
        Objects.requireNonNull(approvalPolicy, "approvalPolicy 不能为空");
        Objects.requireNonNull(harness, "harness 不能为空");
        return () -> new StateGraph(properties.executionBudget(), approvalPolicy, harness)
                .addNode("ops", new OpsNode(terminalService, approvalPolicy, logPublisher))
                .setEntryPoint("ops")
                .addEdge("ops", StateGraph.END);
    }

    /** 声明精确关联 `code-agent` 图的生产 Profile。 */
    @Bean
    AgentProfile codeAgentProfile(ProductionAgentProperties properties) {
        Objects.requireNonNull(properties, "properties 不能为空");
        return new AgentProfile(
                "code-agent",
                "code-agent",
                "Agent4J Code Agent",
                "执行规划、代码增量修改、沙箱测试与浏览器审查",
                Set.of(TaskType.CODE, TaskType.VISION, TaskType.QUICK_CLASSIFICATION),
                Set.of("AstService", "SandboxTerminalService", "BrowserAutomation"),
                properties.executionBudget());
    }

    GraphFactory codeAgentGraph(
            ProductionAgentProperties properties,
            ModelRouter modelRouter,
            MemoryContextProvider memoryContextProvider,
            KnowledgeContextProvider knowledgeContextProvider,
            SandboxTerminalService terminalService,
            BrowserAutomation browserAutomation,
            AstService astService,
            WorkspaceSnapshotService snapshotService,
            RunLogPublisher logPublisher,
            ObjectMapper objectMapper,
            HarnessHookChain harness) {
        BrowserSessionRegistry browserSessions =
                new BrowserSessionRegistry(PlaywrightBrowserService::new);
        CliCommandCatalog commandCatalog = productionCliCommandCatalog();
        return codeAgentGraph(
                properties,
                new KnowledgeProperties(true, 4_000),
                modelRouter,
                memoryContextProvider,
                knowledgeContextProvider,
                terminalService,
                browserAutomation,
                astService,
                snapshotService,
                logPublisher,
                objectMapper,
                harness,
                SecurityViolationSink.noop(),
                standaloneToolRegistry(
                        astService,
                        objectMapper,
                        browserSessions,
                        properties.browserTimeout()),
                commandCatalog,
                standaloneApprovalPolicy(properties, objectMapper, commandCatalog),
                browserSessions);
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
                KnowledgeContextProvider.empty(),
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
                KnowledgeContextProvider.empty(),
                terminalService,
                browserAutomation,
                astService,
                snapshotService,
                logPublisher,
                objectMapper,
                HarnessHookChain.noop());
    }

    GraphFactory codeAgentGraph(
            ProductionAgentProperties properties,
            ModelRouter modelRouter,
            MemoryContextProvider memoryContextProvider,
            KnowledgeContextProvider knowledgeContextProvider,
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
                knowledgeContextProvider,
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
            KnowledgeContextProvider knowledgeContextProvider,
            SandboxTerminalService terminalService,
            BrowserAutomation browserAutomation,
            AstService astService,
            WorkspaceSnapshotService snapshotService,
            RunLogPublisher logPublisher,
            ObjectMapper objectMapper,
            HarnessHookChain harness,
            SecurityViolationSink securityViolationSink,
            int knowledgeMaxTokens,
            ToolRegistry toolRegistry,
            CliCommandCatalog commandCatalog,
            CliApprovalInterruptPolicy approvalPolicy,
            BrowserSessionRegistry browserSessions) {
        var promptCatalog = PlannerPromptTemplates.catalog();
        PlannerNode planner = new PlannerNode(
                modelRouter,
                memoryContextProvider,
                5,
                knowledgeContextProvider,
                knowledgeMaxTokens,
                objectMapper,
                promptCatalog,
                PlannerNode.defaultContextWindowManager(),
                new ModelIntentClassifier(
                        new ModelRouterIntentModel(modelRouter),
                        objectMapper,
                        promptCatalog),
                properties.plannerContextMaxTokens(),
                new DefaultPromptInjectionDetector(),
                securityViolationSink);
        CoderNode coder = new CoderNode(
                astService, modelRouter, objectMapper, snapshotService, toolRegistry,
                commandCatalog);
        OpsNode ops = new OpsNode(terminalService, approvalPolicy, logPublisher);
        ReviewerNode reviewer = new ReviewerNode(
                browserAutomation, modelRouter, objectMapper, properties.browserTimeout());
        GuiAgentNode gui = new GuiAgentNode(
                browserSessions,
                modelRouter,
                objectMapper,
                toolRegistry,
                properties.browserTimeout(),
                properties.maxSteps());
        SkillCatalog skillCatalog = productionSkillCatalog(toolRegistry, objectMapper);
        ToolAgentNode toolAgent = new ToolAgentNode(
                modelRouter, toolRegistry, objectMapper, skillCatalog, properties.maxSteps());
        return new StateGraph(
                properties.executionBudget(), approvalPolicy, harness)
                .addNode("planner", planner)
                .addNode("coder", coder)
                .addNode("ops", ops)
                .addNode("reviewer", reviewer)
                .addNode(REVIEWER_FAILURE_NODE, state -> state.withVariable(
                        ReviewerNode.ERROR_KEY, reviewerFailure(state)))
                .addNode("gui", gui)
                .addNode(TOOL_ROUTE, toolAgent)
                .setEntryPoint("planner")
                .addConditionalEdges(
                        "planner",
                        state -> plannerGraphRoute(state),
                        Map.of(
                                PlannerNode.CHAT_ROUTE, StateGraph.END,
                                PlannerNode.KNOWLEDGE_ROUTE, StateGraph.END,
                                GUI_ROUTE, "gui",
                                TOOL_ROUTE, TOOL_ROUTE,
                                CODER_ROUTE, "coder",
                                PlannerNode.FAILED_ROUTE, StateGraph.END))
                .addEdge(TOOL_ROUTE, StateGraph.END)
                .addConditionalEdges(
                        "coder",
                        state -> state.variables().containsKey(CoderNode.ERROR_KEY)
                                ? PlannerNode.FAILED_ROUTE
                                : "continue",
                        Map.of(PlannerNode.FAILED_ROUTE, StateGraph.END, "continue", "ops"))
                .addEdge("ops", "reviewer")
                .addConditionalEdges(
                        "reviewer",
                        state -> reviewerRoute(state, properties.maxRepairAttempts()),
                        Map.of(
                                REPAIR_ROUTE, "coder",
                                FINISH_ROUTE, StateGraph.END,
                                FAILURE_ROUTE, REVIEWER_FAILURE_NODE))
                .addEdge(REVIEWER_FAILURE_NODE, StateGraph.END);
    }

    private SkillCatalog productionSkillCatalog(
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper) {
        if (toolRegistry.find(ImageGenerationTool.NAME).isEmpty()) {
            return null;
        }
        return new SkillCatalog(List.of(new SkillDefinition(
                "image-generation",
                "1.0.0",
                "通过 Images API 生成图片并返回图片工件",
                List.of("生成图片", "生成一张", "生图", "画一张", "绘制图片"),
                List.of(ImageGenerationTool.NAME),
                "先调用 image.generate，确认工具返回图片工件后再向用户说明生成结果")),
                toolRegistry,
                objectMapper);
    }

    private ToolRegistry standaloneToolRegistry(
            AstService astService,
            ObjectMapper objectMapper,
            BrowserSessionRegistry browserSessions,
            Duration browserTimeout) {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(CodePatchTool.definition(astService, objectMapper));
        registry.registerAll(BrowserToolDefinitions.definitions(
                browserSessions, objectMapper, browserTimeout));
        return registry;
    }

    private CliApprovalInterruptPolicy standaloneApprovalPolicy(
            ProductionAgentProperties properties,
            ObjectMapper objectMapper,
            CliCommandCatalog commandCatalog) {
        return new CliApprovalInterruptPolicy(
                commandCatalog,
                workspaceTargetResolver(properties),
                properties.commandTimeout(),
                objectMapper);
    }

    String plannerRoute(com.agent.core.engine.AgentState state) {
        String route = state.variables().get(PlannerNode.ROUTE_KEY);
        if (route == null) {
            throw new IllegalStateException("缺少状态变量: " + PlannerNode.ROUTE_KEY);
        }
        return switch (route) {
            case PlannerNode.CHAT_ROUTE -> PlannerNode.CHAT_ROUTE;
            case PlannerNode.KNOWLEDGE_ROUTE -> PlannerNode.KNOWLEDGE_ROUTE;
            case PlannerNode.AGENT_ROUTE -> PlannerNode.AGENT_ROUTE;
            case PlannerNode.FAILED_ROUTE -> PlannerNode.FAILED_ROUTE;
            default -> throw new IllegalStateException("未知 Planner 路由: " + route);
        };
    }

    /** 将 Planner Agent 路由细分为代码链或独立 GUI 链。 */
    String plannerGraphRoute(com.agent.core.engine.AgentState state) {
        String route = plannerRoute(state);
        if (!PlannerNode.AGENT_ROUTE.equals(route)) {
            return route;
        }
        String taskKindValue = state.variables().get(PlannerNode.TASK_KIND_KEY);
        if (taskKindValue == null || taskKindValue.isBlank()) {
            throw new IllegalStateException(
                    "Agent 路由缺少状态变量: " + PlannerNode.TASK_KIND_KEY);
        }
        TaskKind taskKind;
        try {
            taskKind = TaskKind.valueOf(taskKindValue);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("未知任务类别: " + taskKindValue, exception);
        }
        return switch (taskKind) {
            case BROWSER_OPERATION -> GUI_ROUTE;
            case TOOL_OPERATION -> TOOL_ROUTE;
            default -> CODER_ROUTE;
        };
    }

    String reviewerRoute(
            com.agent.core.engine.AgentState state,
            int maxRepairAttempts) {
        String approved = state.variables().get(ReviewerNode.APPROVED_KEY);
        if ("true".equals(approved)) {
            return FINISH_ROUTE;
        }
        if (!"false".equals(approved)) {
            return FINISH_ROUTE;
        }
        String attempts = state.variables().get(CoderNode.ATTEMPT_KEY);
        if (attempts == null) {
            throw new IllegalStateException("审查拒绝时缺少状态变量: " + CoderNode.ATTEMPT_KEY);
        }
        return Integer.parseInt(attempts) < maxRepairAttempts
                ? REPAIR_ROUTE
                : FAILURE_ROUTE;
    }

    private String reviewerFailure(com.agent.core.engine.AgentState state) {
        String feedback = state.variables().get(ReviewerNode.FEEDBACK_KEY);
        String summary = state.variables().get(ReviewerNode.SUMMARY_KEY);
        String detail = feedback == null || feedback.isBlank() ? summary : feedback;
        return detail == null || detail.isBlank()
                ? "最终审查未通过且代码修复次数已耗尽"
                : "最终审查未通过且代码修复次数已耗尽: " + detail;
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

    /** 按当前轮次的真实工作区目录解析 PTY 或 Docker 执行目标。 */
    @Bean
    WorkspaceTerminalTargetResolver workspaceTargetResolver(
            ProductionAgentProperties properties) {
        Objects.requireNonNull(properties, "properties 不能为空");
        return workspacePath -> {
            Path root = realPath(properties.workspace(), "配置工作区");
            Path workspace = realPath(workspacePath, "当前工作区");
            if (!workspace.startsWith(root)) {
                throw new IllegalArgumentException(
                        "当前工作区必须位于配置工作区内: " + workspace);
            }
            return switch (properties.executionMode()) {
                case "PTY" -> new PtyTarget(
                        Path.of(properties.bashExecutable()), workspace);
                case "DOCKER" -> dockerWorkspaceTarget(
                        properties, root, workspace);
                default -> throw new IllegalArgumentException(
                        "executionMode 必须精确为 DOCKER 或 PTY");
            };
        };
    }

    private DockerTarget dockerWorkspaceTarget(
            ProductionAgentProperties properties,
            Path root,
            Path workspace) {
        if (properties.workspaceSourceContainer().isBlank()) {
            return new DockerTarget(
                    properties.dockerImage(),
                    workspace,
                    properties.containerWorkspace());
        }
        String relativePath = root.relativize(workspace)
                .toString()
                .replace('\\', '/');
        return new DockerTarget(
                properties.dockerImage(),
                workspace,
                properties.containerWorkspace(),
                new DockerTarget.ContainerWorkspaceSource(
                        properties.workspaceSourceContainer(),
                        properties.workspaceSourcePath(),
                        relativePath));
    }

    private Path realPath(Path path, String label) {
        Objects.requireNonNull(path, label + " 不能为空");
        try {
            return path.toRealPath();
        } catch (IOException exception) {
            throw new IllegalArgumentException(label + " 必须是现有目录: " + path, exception);
        }
    }
}
