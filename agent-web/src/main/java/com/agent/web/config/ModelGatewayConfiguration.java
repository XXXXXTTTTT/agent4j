package com.agent.web.config;

import com.agent.core.llm.LlmClient;
import com.agent.core.llm.ModelEndpoint;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.TaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 将环境配置适配为 Core 使用的构造器注入模型路由。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agent.llm.enabled", havingValue = "true")
@EnableConfigurationProperties(ModelGatewayProperties.class)
public class ModelGatewayConfiguration {

    /** 创建带有界连接和读取超时的 Apache HTTP 客户端。 */
    @Bean(destroyMethod = "close")
    CloseableHttpClient modelGatewayHttpClient() {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(5))
                .setConnectionRequestTimeout(Timeout.ofSeconds(5))
                .setResponseTimeout(Timeout.ofSeconds(45))
                .build();
        return HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .build();
    }

    /** 创建共享 OpenAI 兼容客户端。 */
    @Bean(destroyMethod = "close")
    LlmClient modelGatewayClient(
            ModelGatewayProperties properties,
            ObjectMapper objectMapper,
            CloseableHttpClient httpClient) {
        properties.validate();
        RestClient restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .defaultHeader(
                        HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .build();
        return new LlmClient(
                restClient,
                objectMapper,
                properties.chatCompletionsPath(),
                properties.baseUrl() + properties.chatCompletionsPath());
    }

    /** 为三类任务创建主模型与统一降级模型链。 */
    @Bean
    ModelRouter modelRouter(
            ModelGatewayProperties properties,
            LlmClient client) {
        properties.validate();
        CircuitBreakerConfig breakerConfig = CircuitBreakerConfig.ofDefaults();
        EnumMap<TaskType, List<ModelEndpoint>> routes = new EnumMap<>(TaskType.class);
        routes.put(TaskType.CODE, List.of(
                endpoint("code-primary", properties.codeModel(), client, breakerConfig),
                endpoint("code-fallback", properties.fallbackModel(), client, breakerConfig)));
        routes.put(TaskType.VISION, List.of(
                endpoint("vision-primary", properties.visionModel(), client, breakerConfig),
                endpoint("vision-fallback", properties.fallbackModel(), client, breakerConfig)));
        routes.put(TaskType.QUICK_CLASSIFICATION, List.of(
                endpoint("quick-primary", properties.quickClassificationModel(), client, breakerConfig),
                endpoint("quick-fallback", properties.fallbackModel(), client, breakerConfig)));
        return new ModelRouter(Map.copyOf(routes));
    }

    private static ModelEndpoint endpoint(
            String name,
            String model,
            LlmClient client,
            CircuitBreakerConfig breakerConfig) {
        return new ModelEndpoint(
                name,
                model,
                client,
                CircuitBreaker.of(name, breakerConfig));
    }
}
