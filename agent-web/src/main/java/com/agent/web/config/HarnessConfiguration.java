package com.agent.web.config;

import com.agent.core.engine.AgentRunService;
import com.agent.core.engine.Checkpointer;
import com.agent.core.engine.GraphFactory;
import com.agent.core.engine.GraphRegistry;
import com.agent.core.profile.AgentProfile;
import com.agent.core.profile.AgentProfileRegistry;
import com.agent.core.conversation.ConversationContextProvider;
import com.agent.web.log.InMemoryRunLogEventBus;
import com.agent.web.persistence.JdbcCheckpointer;
import com.agent.web.persistence.JdbcConversationRepository;
import com.agent.web.persistence.JdbcModelConfigurationRepository;
import com.agent.web.model.ModelConfigurationRepository;
import com.agent.web.model.ModelConfigurationService;
import com.agent.web.conversation.ConversationRunProjector;
import com.agent.web.conversation.ConversationService;
import com.agent.web.conversation.JdbcConversationContextProvider;
import com.agent.web.audit.ConversationAuditSink;
import com.agent.web.audit.AuditTextRedactor;
import com.agent.web.audit.Slf4jConversationAuditSink;
import com.agent.web.identity.ActorResolver;
import com.agent.web.identity.ConfiguredActorResolver;
import com.agent.web.workspace.WorkspaceAccessService;
import com.agent.web.workspace.WorkspaceBootstrap;
import com.agent.web.workspace.WorkspaceDirectoryBrowser;
import com.agent.web.workspace.WorkspaceImportService;
import com.agent.web.mcp.catalog.OfficialMcpCatalogClient;
import com.agent.web.mcp.installation.McpInstallationRepository;
import com.agent.web.mcp.installation.McpInstallationService;
import com.agent.web.mcp.runtime.DockerMcpStdioRunner;
import com.agent.web.mcp.runtime.FileSystemMcpRuntimeMaterialProvider;
import com.agent.web.mcp.runtime.McpInstallationRuntime;
import com.agent.web.mcp.runtime.McpRuntimeMaterialProvider;
import com.agent.web.mcp.runtime.McpRuntimeSecretProvider;
import com.agent.web.mcp.runtime.McpRuntimeRecovery;
import com.agent.web.mcp.runtime.DockerMcpMaterialPreparationRunner;
import com.agent.web.mcp.runtime.McpMaterialPreparationRunner;
import com.agent.web.mcp.runtime.McpMaterialPreparationService;
import com.agent.web.persistence.JdbcMcpInstallationRepository;
import com.agent.web.persistence.JdbcSkillInstallationRepository;
import com.agent.web.persistence.JdbcCapabilityManagementAuditSink;
import com.agent.web.skill.GitHubSkillCatalogClient;
import com.agent.web.skill.GitHubSkillInstallationService;
import com.agent.web.skill.SkillInstallationRepository;
import com.agent.web.capability.CapabilityManagementAuditSink;
import com.agent.core.tool.ToolRegistry;
import com.agent.web.trace.InMemoryTraceEventBus;
import com.agent.web.trace.RunLifecycleEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.core.env.Environment;

import java.time.Clock;
import java.time.Duration;
import java.net.URI;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.agent.core.trace.TraceEventPublisher;
import com.agent.web.observability.OpenTelemetryRunTracePublisher;
import com.agent.rag.memory.RunBadCaseAttributor;

/** 装配 Harness 的持久化、图注册、Trace 与运行服务。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ProductionAgentProperties.class, WorkspaceImportProperties.class, McpRuntimeProperties.class})
public class HarnessConfiguration {

    /** 提供持久化 Checkpoint 使用的 UTC 时钟。 */
    @Bean
    Clock harnessClock() {
        return Clock.systemUTC();
    }

    /** 非生产测试与本地只读页面使用的默认单机身份。 */
    @Bean
    @ConditionalOnMissingBean(ActorResolver.class)
    ActorResolver defaultActorResolver() {
        return new ConfiguredActorResolver("local", "本地用户");
    }

    /** 创建 PostgreSQL Checkpointer 适配器。 */
    @Bean
    Checkpointer checkpointer(
            JdbcClient jdbcClient,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper,
            Clock harnessClock) {
        return new JdbcCheckpointer(
                jdbcClient,
                new TransactionTemplate(transactionManager),
                objectMapper,
                harnessClock);
    }

    /** 创建会话和工作区共用的 JDBC 权威仓储。 */
    @Bean
    @ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
    JdbcConversationRepository conversationRepository(
            JdbcClient jdbcClient,
            PlatformTransactionManager transactionManager,
            Clock harnessClock) {
        return new JdbcConversationRepository(
                jdbcClient,
                new TransactionTemplate(transactionManager),
                harnessClock);
    }

    /** 创建用户模型池配置仓储。 */
    @Bean
    @ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
    ModelConfigurationRepository modelConfigurationRepository(
            JdbcClient jdbcClient,
            PlatformTransactionManager transactionManager) {
        return new JdbcModelConfigurationRepository(
                jdbcClient, new TransactionTemplate(transactionManager));
    }

    /** 创建用户模型池配置服务。 */
    @Bean
    @ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
    ModelConfigurationService modelConfigurationService(
            ModelConfigurationRepository repository,
            ActorResolver actorResolver,
            Clock harnessClock) {
        return new ModelConfigurationService(repository, actorResolver, harnessClock);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
    OfficialMcpCatalogClient officialMcpCatalogClient(ObjectMapper objectMapper) {
        return new OfficialMcpCatalogClient(objectMapper,
                URI.create("https://api.github.com/repos/modelcontextprotocol/servers/"),
                "main", Duration.ofSeconds(10), 2_000_000, Duration.ofMinutes(10));
    }

    @Bean
    @ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
    GitHubSkillCatalogClient gitHubSkillCatalogClient(ObjectMapper objectMapper) {
        return new GitHubSkillCatalogClient(objectMapper, URI.create("https://api.github.com/"),
                Duration.ofSeconds(10), 512_000);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
    McpInstallationRepository mcpInstallationRepository(
            JdbcClient jdbcClient, PlatformTransactionManager transactionManager, ObjectMapper objectMapper) {
        return new JdbcMcpInstallationRepository(jdbcClient, new TransactionTemplate(transactionManager), objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
    SkillInstallationRepository skillInstallationRepository(
            JdbcClient jdbcClient, PlatformTransactionManager transactionManager, ObjectMapper objectMapper) {
        return new JdbcSkillInstallationRepository(jdbcClient, new TransactionTemplate(transactionManager), objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
    CapabilityManagementAuditSink capabilityManagementAuditSink(
            JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
        return new JdbcCapabilityManagementAuditSink(
                jdbcClient, new TransactionTemplate(transactionManager), UUID::randomUUID);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
    McpInstallationService mcpInstallationService(
            ActorResolver actorResolver, WorkspaceAccessService workspaceAccess,
            McpInstallationRepository repository, CapabilityManagementAuditSink auditSink, Clock harnessClock,
            McpRuntimeProperties runtimeProperties) {
        return new McpInstallationService(actorResolver, workspaceAccess, repository,
                auditSink, harnessClock, Duration.ofMinutes(5), UUID::randomUUID, runtimeProperties.image());
    }

    /** 以持久化物料记录和受配置根目录构造 MCP 运行物料校验端口。 */
    @Bean
    @ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
    McpRuntimeMaterialProvider mcpRuntimeMaterialProvider(
            McpRuntimeProperties properties, McpInstallationRepository repository) {
        return new FileSystemMcpRuntimeMaterialProvider(properties.materialRoot(),
                snapshot -> repository.findPreparedMaterial(snapshot.snapshotId())
                        .map(value -> new McpRuntimeMaterialProvider.PreparedMaterial(
                                value.directory(), value.sha256(), value.command(), value.arguments()))
                        .orElse(null));
    }

    /** 默认只传递快照显式声明的环境变量名。 */
    @Bean
    @ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
    McpRuntimeSecretProvider mcpRuntimeSecretProvider() {
        return McpRuntimeSecretProvider.declaredNamesOnly();
    }

    /** 物料准备单独使用短生命周期 Docker 容器，不复用持续 MCP runner。 */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
    McpMaterialPreparationRunner mcpMaterialPreparationRunner(McpRuntimeProperties properties,
                                                               ObjectMapper objectMapper, Clock harnessClock) {
        return new DockerMcpMaterialPreparationRunner(properties.materialRoot(), properties.image(),
                properties.pythonPreparationImage(), objectMapper, harnessClock);
    }

    /** 将物料下载和安装确认、运行生命周期明确隔离。 */
    @Bean
    @ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
    McpMaterialPreparationService mcpMaterialPreparationService(ActorResolver actorResolver,
                                                                  WorkspaceAccessService workspaceAccess,
                                                                  McpInstallationRepository repository,
                                                                  McpMaterialPreparationRunner runner,
                                                                  Clock harnessClock) {
        return new McpMaterialPreparationService(actorResolver, workspaceAccess, repository, runner, harnessClock);
    }

    /** Docker stdio 运行器由 Spring 在应用关闭时统一关闭。 */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
    DockerMcpStdioRunner dockerMcpStdioRunner() {
        return new DockerMcpStdioRunner();
    }

    /** 将受确认安装接入 MCP Docker 生命周期服务。 */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
    @ConditionalOnBean(ToolRegistry.class)
    McpInstallationRuntime mcpInstallationRuntime(
            ActorResolver actorResolver, WorkspaceAccessService workspaceAccess,
            McpInstallationRepository repository, McpRuntimeMaterialProvider materialProvider,
            McpRuntimeSecretProvider secretProvider, DockerMcpStdioRunner runner, ToolRegistry toolRegistry,
            ObjectMapper objectMapper, McpRuntimeProperties properties, McpGatewayProperties gatewayProperties,
            Clock harnessClock) {
        return new McpInstallationRuntime(actorResolver, workspaceAccess, repository, materialProvider, secretProvider,
                runner, toolRegistry, objectMapper, new McpInstallationRuntime.McpRuntimeConfiguration(
                        gatewayProperties.protocolVersion(), gatewayProperties.clientName(), gatewayProperties.clientVersion(),
                        properties.materialContainerDirectory(), properties.materialSourceContainer(), properties.materialSourcePath(),
                        properties.containerWorkingDirectory(), properties.memoryBytes(), properties.nanoCpus(), properties.pidsLimit(),
                        properties.maxStdoutFrameBytes(), properties.maxStdoutBufferedBytes(), properties.maxStderrBytes(),
                        properties.requestTimeout(), properties.toolTimeout(), properties.drainTimeout()), harnessClock);
    }

    /** 应用就绪后恢复已中断的 MCP 容器生命周期。 */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
    @ConditionalOnBean(McpInstallationRuntime.class)
    McpRuntimeRecovery mcpRuntimeRecovery(
            McpInstallationRepository repository, DockerMcpStdioRunner runner, McpInstallationRuntime runtime) {
        return new McpRuntimeRecovery(repository, runner, runtime);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
    @ConditionalOnBean(ToolRegistry.class)
    GitHubSkillInstallationService gitHubSkillInstallationService(
            GitHubSkillCatalogClient client, ToolRegistry toolRegistry, ActorResolver actorResolver,
            WorkspaceAccessService workspaceAccess, SkillInstallationRepository repository,
            CapabilityManagementAuditSink auditSink, Clock harnessClock) {
        return new GitHubSkillInstallationService(client, toolRegistry, actorResolver, workspaceAccess,
                repository, auditSink, harnessClock, Duration.ofMinutes(5), UUID::randomUUID);
    }

    /** 将 PostgreSQL 完成轮次组装为核心短期上下文。 */
    @Bean
    @ConditionalOnBean(JdbcConversationRepository.class)
    ConversationContextProvider conversationContextProvider(
            JdbcConversationRepository repository) {
        return new JdbcConversationContextProvider(repository);
    }

    /** 单机配置身份边界；后续可替换为网关认证主体。 */
    @Bean
    @ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
    ActorResolver actorResolver(ProductionAgentProperties properties) {
        return new ConfiguredActorResolver(properties.userId(), properties.userId());
    }

    /** 工作区路径与成员权限门禁。 */
    @Bean
    @ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
    WorkspaceAccessService workspaceAccessService(
            JdbcConversationRepository repository,
            ProductionAgentProperties properties,
            Clock harnessClock) {
        return new WorkspaceAccessService(repository, properties.workspace(), harnessClock);
    }

    /** 启动时幂等创建配置用户和默认 OWNER 工作区。 */
    @Bean
    @ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
    WorkspaceBootstrap workspaceBootstrap(
            WorkspaceAccessService workspaceAccessService,
            ActorResolver actorResolver,
            ProductionAgentProperties properties) {
        return new WorkspaceBootstrap(
                workspaceAccessService,
                actorResolver,
                properties.workspace(),
                properties.repositoryId());
    }

    /** 创建当前挂载根内的目录浏览器。 */
    @Bean
    @ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
    WorkspaceDirectoryBrowser workspaceDirectoryBrowser(ProductionAgentProperties properties) {
        return new WorkspaceDirectoryBrowser(properties.workspace());
    }

    /** 创建受资源上限保护的外部项目 ZIP 导入服务。 */
    @Bean
    @ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
    WorkspaceImportService workspaceImportService(
            WorkspaceAccessService workspaceAccessService,
            ProductionAgentProperties productionProperties,
            WorkspaceImportProperties importProperties,
            Clock harnessClock) {
        return new WorkspaceImportService(
                workspaceAccessService, productionProperties.workspace(), importProperties, harnessClock);
    }

    /** 绑定当前会话的 Run 启动服务。 */
    @Bean
    @ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
    ConversationService conversationService(
            JdbcConversationRepository repository,
            WorkspaceAccessService workspaceAccessService,
            ConversationContextProvider conversationContextProvider,
            ActorResolver actorResolver,
            AgentRunService agentRunService,
            ConversationRunProjector conversationRunProjector,
            ConversationAuditSink conversationAuditSink,
            Clock harnessClock) {
        return new ConversationService(
                repository,
                workspaceAccessService,
                conversationContextProvider,
                actorResolver,
                agentRunService::start,
                conversationRunProjector,
                conversationAuditSink,
                harnessClock);
    }

    /** 创建覆盖运行配置和常见令牌格式的审计文本脱敏器。 */
    @Bean
    AuditTextRedactor auditTextRedactor(Environment environment) {
        return new AuditTextRedactor(List.of(
                environment.getProperty("agent.llm.api-key", ""),
                environment.getProperty("spring.datasource.password", ""),
                environment.getProperty("agent.observability.authorization", "")));
    }

    /** 创建写入 Logback JSON Lines 文件的会话业务审计端口。 */
    @Bean
    ConversationAuditSink conversationAuditSink(
            ObjectMapper objectMapper,
            AuditTextRedactor redactor) {
        return new Slf4jConversationAuditSink(objectMapper, redactor);
    }

    /** 将 Run 终态投影回会话轮次。 */
    @Bean
    @ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
    ConversationRunProjector conversationRunProjector(
            JdbcConversationRepository repository,
            Checkpointer checkpointer,
            ConversationAuditSink conversationAuditSink,
            Clock harnessClock) {
        return new ConversationRunProjector(
                repository, checkpointer, conversationAuditSink, harnessClock);
    }

    /** 使用精确 Bean 名到 GraphFactory 的映射创建图注册表。 */
    @Bean
    GraphRegistry graphRegistry(Map<String, GraphFactory> graphFactories) {
        return new GraphRegistry(graphFactories);
    }

    /** 使用构造器声明的 Profile Bean 创建只读注册表。 */
    @Bean
    AgentProfileRegistry agentProfileRegistry(
            Map<String, AgentProfile> profiles,
            GraphRegistry graphRegistry) {
        return new AgentProfileRegistry(profiles, graphRegistry);
    }

    /** 创建进程内实时 Trace 总线。 */
    @Bean(destroyMethod = "close")
    InMemoryTraceEventBus traceEventBus() {
        return new InMemoryTraceEventBus();
    }

    /** 创建进程内实时 Run 日志总线。 */
    @Bean(destroyMethod = "close")
    InMemoryRunLogEventBus runLogEventBus() {
        return new InMemoryRunLogEventBus();
    }

    /** 组合 Trace 发布与 Run 终态日志清理。 */
    @Bean
    RunLifecycleEventPublisher runLifecycleEventPublisher(
            InMemoryTraceEventBus traceEventBus,
            InMemoryRunLogEventBus runLogEventBus,
            ObjectProvider<OpenTelemetryRunTracePublisher> otelPublisher,
            ObjectProvider<RunBadCaseAttributor> badCaseAttributor,
            ObjectProvider<ConversationRunProjector> conversationProjector) {
        List<TraceEventPublisher> publishers = new ArrayList<>();
        publishers.add(traceEventBus);
        otelPublisher.ifAvailable(publishers::add);
        badCaseAttributor.ifAvailable(publishers::add);
        conversationProjector.ifAvailable(publishers::add);
        return new RunLifecycleEventPublisher(publishers, runLogEventBus);
    }

    /** 创建基于虚拟线程的 Agent Run 服务。 */
    @Bean(destroyMethod = "close")
    AgentRunService agentRunService(
            Checkpointer checkpointer,
            GraphRegistry graphRegistry,
            RunLifecycleEventPublisher lifecycleEventPublisher) {
        return new AgentRunService(
                checkpointer, graphRegistry, lifecycleEventPublisher);
    }
}
