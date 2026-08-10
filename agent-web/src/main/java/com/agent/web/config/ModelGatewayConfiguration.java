package com.agent.web.config;

import com.agent.core.llm.LlmClient;
import com.agent.core.llm.InferenceAdmissionController;
import com.agent.core.llm.InferenceBudget;
import com.agent.core.llm.InferenceCapability;
import com.agent.core.llm.InferenceProtocol;
import com.agent.core.llm.InferenceServiceContract;
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
import java.util.Set;
import java.net.URI;
import java.net.URISyntaxException;

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
        ResolvedEndpoint endpoint = resolveEndpoint(
                properties.baseUrl(), properties.chatCompletionsPath());
        RestClient restClient = RestClient.builder()
                .baseUrl(endpoint.transportBaseUrl())
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .defaultHeader(
                        HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .build();
        return new LlmClient(
                restClient,
                objectMapper,
                endpoint.requestPath(),
                endpoint.requestUrl());
    }

    /**
     * 将网关基础地址和 Chat Completions 路径解析为唯一请求地址。
     * 基础地址可以包含版本前缀，路径已经包含该前缀时不会重复拼接。
     */
    static ResolvedEndpoint resolveEndpoint(String baseUrl, String configuredPath) {
        URI base;
        try {
            base = new URI(baseUrl);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("agent.llm.base-url URI 无效", exception);
        }
        String basePath = normalizePath(base.getPath());
        String path = configuredPath == null ? "" : configuredPath.trim();
        if (path.isEmpty() || !path.startsWith("/")) {
            throw new IllegalArgumentException(
                    "agent.llm.chat-completions-path 必须以 / 开头");
        }
        String finalPath = basePath.isEmpty() || path.equals(basePath)
                || path.startsWith(basePath + "/")
                ? path
                : joinPaths(basePath, path);
        String transportBaseUrl;
        try {
            transportBaseUrl = new URI(
                    base.getScheme(),
                    base.getUserInfo(),
                    base.getHost(),
                    base.getPort(),
                    null,
                    null,
                    null).toString();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("agent.llm.base-url URI 无效", exception);
        }
        return new ResolvedEndpoint(
                transportBaseUrl,
                finalPath,
                transportBaseUrl + finalPath);
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "";
        }
        String normalized = path.trim();
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    private static String joinPaths(String left, String right) {
        return normalizePath(left) + "/" + right.substring(1);
    }

    record ResolvedEndpoint(
            String transportBaseUrl,
            String requestPath,
            String requestUrl) {
    }

    /** 为三类任务创建主模型与统一降级模型链。 */
    @Bean
    ModelRouter modelRouter(
            ModelGatewayProperties properties,
            LlmClient client) {
        properties.validate();
        CircuitBreakerConfig breakerConfig = CircuitBreakerConfig.ofDefaults();
        InferenceBudget budget = new InferenceBudget(
                properties.maxConcurrentRequests(),
                properties.maxRequestsPerMinute(),
                properties.queueTimeout());
        EnumMap<TaskType, List<ModelEndpoint>> routes = new EnumMap<>(TaskType.class);
        routes.put(TaskType.CODE, List.of(
                endpoint("code-primary", properties.codeModel(), client, breakerConfig,
                        properties.codeCapabilities(), budget),
                endpoint("code-fallback", properties.fallbackModel(), client, breakerConfig,
                        properties.fallbackCapabilities(), budget)));
        routes.put(TaskType.VISION, List.of(
                endpoint("vision-primary", properties.visionModel(), client, breakerConfig,
                        properties.visionCapabilities(), budget),
                endpoint("vision-fallback", properties.fallbackModel(), client, breakerConfig,
                        properties.fallbackCapabilities(), budget)));
        routes.put(TaskType.QUICK_CLASSIFICATION, List.of(
                endpoint("quick-primary", properties.quickClassificationModel(), client, breakerConfig,
                        properties.quickClassificationCapabilities(), budget),
                endpoint("quick-fallback", properties.fallbackModel(), client, breakerConfig,
                        properties.fallbackCapabilities(), budget)));
        return new ModelRouter(Map.copyOf(routes));
    }

    private static ModelEndpoint endpoint(
            String name,
            String model,
            LlmClient client,
            CircuitBreakerConfig breakerConfig,
            Set<InferenceCapability> capabilities,
            InferenceBudget budget) {
        return new ModelEndpoint(
                name,
                model,
                client,
                CircuitBreaker.of(name, breakerConfig),
                new InferenceServiceContract(
                        name,
                        model,
                        InferenceProtocol.OPENAI_CHAT_COMPLETIONS,
                        capabilities),
                new InferenceAdmissionController(budget));
    }
}
