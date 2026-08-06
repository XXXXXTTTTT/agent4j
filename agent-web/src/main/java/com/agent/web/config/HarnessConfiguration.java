package com.agent.web.config;

import com.agent.core.engine.AgentRunService;
import com.agent.core.engine.Checkpointer;
import com.agent.core.engine.GraphFactory;
import com.agent.core.engine.GraphRegistry;
import com.agent.core.conversation.ConversationContextProvider;
import com.agent.web.log.InMemoryRunLogEventBus;
import com.agent.web.persistence.JdbcCheckpointer;
import com.agent.web.persistence.JdbcConversationRepository;
import com.agent.web.conversation.ConversationRunProjector;
import com.agent.web.conversation.ConversationService;
import com.agent.web.conversation.JdbcConversationContextProvider;
import com.agent.web.identity.ActorResolver;
import com.agent.web.identity.ConfiguredActorResolver;
import com.agent.web.workspace.WorkspaceAccessService;
import com.agent.web.trace.InMemoryTraceEventBus;
import com.agent.web.trace.RunLifecycleEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.agent.core.trace.TraceEventPublisher;
import com.agent.web.observability.OpenTelemetryRunTracePublisher;
import com.agent.rag.memory.RunBadCaseAttributor;

/** 装配 Harness 的持久化、图注册、Trace 与运行服务。 */
@Configuration(proxyBeanMethods = false)
public class HarnessConfiguration {

    /** 提供持久化 Checkpoint 使用的 UTC 时钟。 */
    @Bean
    Clock harnessClock() {
        return Clock.systemUTC();
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
    @ConditionalOnBean(ProductionAgentProperties.class)
    JdbcConversationRepository conversationRepository(
            JdbcClient jdbcClient,
            PlatformTransactionManager transactionManager,
            Clock harnessClock) {
        return new JdbcConversationRepository(
                jdbcClient,
                new TransactionTemplate(transactionManager),
                harnessClock);
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
    @ConditionalOnBean(ProductionAgentProperties.class)
    ActorResolver actorResolver(ProductionAgentProperties properties) {
        return new ConfiguredActorResolver(properties.userId(), properties.userId());
    }

    /** 工作区路径与成员权限门禁。 */
    @Bean
    @ConditionalOnBean(ProductionAgentProperties.class)
    WorkspaceAccessService workspaceAccessService(
            JdbcConversationRepository repository,
            ProductionAgentProperties properties,
            Clock harnessClock) {
        return new WorkspaceAccessService(repository, properties.workspace(), harnessClock);
    }

    /** 绑定当前会话的 Run 启动服务。 */
    @Bean
    @ConditionalOnBean({ProductionAgentProperties.class, AgentRunService.class})
    ConversationService conversationService(
            JdbcConversationRepository repository,
            WorkspaceAccessService workspaceAccessService,
            ConversationContextProvider conversationContextProvider,
            ActorResolver actorResolver,
            AgentRunService agentRunService,
            Clock harnessClock) {
        return new ConversationService(
                repository,
                workspaceAccessService,
                conversationContextProvider,
                actorResolver,
                agentRunService::start,
                harnessClock);
    }

    /** 将 Run 终态投影回会话轮次。 */
    @Bean
    @ConditionalOnBean({ProductionAgentProperties.class, JdbcConversationRepository.class})
    ConversationRunProjector conversationRunProjector(
            JdbcConversationRepository repository,
            Checkpointer checkpointer,
            Clock harnessClock) {
        return new ConversationRunProjector(repository, checkpointer, harnessClock);
    }

    /** 使用精确 Bean 名到 GraphFactory 的映射创建图注册表。 */
    @Bean
    GraphRegistry graphRegistry(Map<String, GraphFactory> graphFactories) {
        return new GraphRegistry(graphFactories);
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
