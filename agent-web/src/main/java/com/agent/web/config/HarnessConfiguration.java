package com.agent.web.config;

import com.agent.core.engine.AgentRunService;
import com.agent.core.engine.Checkpointer;
import com.agent.core.engine.GraphFactory;
import com.agent.core.engine.GraphRegistry;
import com.agent.web.persistence.JdbcCheckpointer;
import com.agent.web.trace.InMemoryTraceEventBus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Map;

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

    /** 创建基于虚拟线程的 Agent Run 服务。 */
    @Bean(destroyMethod = "close")
    AgentRunService agentRunService(
            Checkpointer checkpointer,
            GraphRegistry graphRegistry,
            InMemoryTraceEventBus traceEventBus) {
        return new AgentRunService(checkpointer, graphRegistry, traceEventBus);
    }
}
