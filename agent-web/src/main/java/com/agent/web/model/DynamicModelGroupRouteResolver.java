package com.agent.web.model;

import com.agent.core.engine.NodeExecutionContext;
import com.agent.core.llm.InferenceAdmissionController;
import com.agent.core.llm.InferenceProtocol;
import com.agent.core.llm.InferenceServiceContract;
import com.agent.core.llm.ModelEndpoint;
import com.agent.core.llm.ModelGroupRouteResolver;
import com.agent.core.llm.TaskType;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** 从当前用户数据库配置解析并缓存运行时模型端点。 */
public final class DynamicModelGroupRouteResolver implements ModelGroupRouteResolver, AutoCloseable {
    private final ModelConfigurationRepository repository;
    private final Function<ModelProviderRuntime, com.agent.core.llm.LlmClient> clientFactory;
    private final CircuitBreakerConfig circuitBreakerConfig;
    private final Map<CacheKey, CachedClient> clients = new HashMap<>();
    private final Map<EndpointKey, CircuitBreaker> breakers = new HashMap<>();
    private final Map<SelectionKey, Integer> weightCursors = new HashMap<>();

    public DynamicModelGroupRouteResolver(
            ModelConfigurationRepository repository,
            Function<ModelProviderRuntime, com.agent.core.llm.LlmClient> clientFactory) {
        this(repository, clientFactory, CircuitBreakerConfig.ofDefaults());
    }

    public DynamicModelGroupRouteResolver(
            ModelConfigurationRepository repository,
            Function<ModelProviderRuntime, com.agent.core.llm.LlmClient> clientFactory,
            CircuitBreakerConfig circuitBreakerConfig) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory 不能为空");
        this.circuitBreakerConfig = Objects.requireNonNull(
                circuitBreakerConfig, "circuitBreakerConfig 不能为空");
    }

    @Override
    public List<ModelEndpoint> resolve(String groupId, TaskType taskType) {
        String userId = NodeExecutionContext.currentState()
                .map(state -> state.variables().get("planner.userId"))
                .filter(value -> value != null && !value.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "模型组解析缺少状态变量: planner.userId"));
        return resolveForUser(userId, groupId, taskType);
    }

    /** 按明确用户标识解析，供配置边界测试和非图调用使用。 */
    public synchronized List<ModelEndpoint> resolveForUser(
            String userId, String groupId, TaskType taskType) {
        requireText(userId, "userId");
        requireText(groupId, "groupId");
        Objects.requireNonNull(taskType, "taskType 不能为空");
        ModelGroupRecord group = repository.findGroups(userId).stream()
                .filter(value -> value.groupId().toString().equals(groupId))
                .findFirst()
                .orElse(null);
        if (group == null) {
            return List.of();
        }
        Map<UUID, ModelEndpointRecord> endpoints = new HashMap<>();
        repository.findEndpoints(userId).forEach(value -> endpoints.put(value.endpointId(), value));
        List<ModelEndpointRecord> enabled = group.endpointIds().stream()
                .map(endpoints::get)
                .filter(Objects::nonNull)
                .filter(ModelEndpointRecord::enabled)
                .sorted(Comparator.comparingInt(ModelEndpointRecord::priority)
                        .thenComparing(Comparator.comparingInt(ModelEndpointRecord::weight).reversed())
                        .thenComparing(value -> value.endpointId().toString()))
                .toList();
        List<ModelEndpointRecord> ordered = weightedOrder(
                enabled, group.groupId(), taskType);
        List<ModelEndpoint> result = new ArrayList<>();
        for (ModelEndpointRecord endpoint : ordered) {
            ModelProviderRuntime runtime = repository.findProviderRuntime(
                            endpoint.providerId(), userId)
                    .orElse(null);
            if (runtime == null) {
                continue;
            }
            com.agent.core.llm.LlmClient client = clientFor(runtime);
            String endpointName = "model-group-" + group.groupId() + "-" + endpoint.endpointId();
            CircuitBreaker breaker = breakers.computeIfAbsent(
                    new EndpointKey(userId, endpoint.endpointId()),
                    ignored -> CircuitBreaker.of(
                            endpointName, circuitBreakerConfig));
            result.add(new ModelEndpoint(
                    endpointName,
                    endpoint.modelId(),
                    client,
                    breaker,
                    new InferenceServiceContract(
                            endpointName,
                            endpoint.modelId(),
                            InferenceProtocol.OPENAI_CHAT_COMPLETIONS,
                            endpoint.capabilities()),
                    InferenceAdmissionController.unlimited()));
        }
        return List.copyOf(result);
    }

    private List<ModelEndpointRecord> weightedOrder(
            List<ModelEndpointRecord> endpoints,
            UUID groupId,
            TaskType taskType) {
        Map<Integer, List<ModelEndpointRecord>> byPriority = new java.util.LinkedHashMap<>();
        endpoints.forEach(endpoint -> byPriority
                .computeIfAbsent(endpoint.priority(), ignored -> new ArrayList<>())
                .add(endpoint));
        List<ModelEndpointRecord> result = new ArrayList<>();
        for (Map.Entry<Integer, List<ModelEndpointRecord>> entry : byPriority.entrySet()) {
            List<ModelEndpointRecord> samePriority = entry.getValue();
            int totalWeight = samePriority.stream().mapToInt(ModelEndpointRecord::weight).sum();
            if (totalWeight <= 0) {
                continue;
            }
            SelectionKey key = new SelectionKey(groupId, taskType, entry.getKey());
            int cursor = weightCursors.getOrDefault(key, 0);
            weightCursors.put(key, (cursor + 1) % totalWeight);
            java.util.LinkedHashSet<UUID> seen = new java.util.LinkedHashSet<>();
            for (int offset = 0; offset < totalWeight; offset++) {
                int slot = (cursor + offset) % totalWeight;
                int boundary = 0;
                for (ModelEndpointRecord endpoint : samePriority) {
                    boundary += endpoint.weight();
                    if (slot < boundary) {
                        seen.add(endpoint.endpointId());
                        break;
                    }
                }
            }
            for (UUID endpointId : seen) {
                samePriority.stream()
                        .filter(endpoint -> endpoint.endpointId().equals(endpointId))
                        .findFirst()
                        .ifPresent(result::add);
            }
        }
        return List.copyOf(result);
    }

    private com.agent.core.llm.LlmClient clientFor(ModelProviderRuntime runtime) {
        CacheKey key = new CacheKey(runtime.providerId(), runtime.ownerUserId());
        ClientConfiguration configuration = ClientConfiguration.from(runtime);
        CachedClient cached = clients.get(key);
        if (cached != null && cached.configuration().equals(configuration)) {
            return cached.client();
        }
        com.agent.core.llm.LlmClient client = Objects.requireNonNull(
                clientFactory.apply(runtime), "clientFactory 返回的 LlmClient 不能为空");
        clients.put(key, new CachedClient(configuration, client));
        if (cached != null && cached.client() != client) {
            cached.client().close();
        }
        return client;
    }

    @Override
    public synchronized void close() {
        clients.values().stream().map(CachedClient::client).distinct()
                .forEach(com.agent.core.llm.LlmClient::close);
        clients.clear();
        breakers.clear();
        weightCursors.clear();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }

    private record CacheKey(UUID providerId, String ownerUserId) {
    }

    private record SelectionKey(UUID groupId, TaskType taskType, int priority) {
    }

    private record EndpointKey(String userId, UUID endpointId) {
    }

    /** 用完整运行时配置判定客户端是否可复用，避免哈希碰撞复用过期密钥。 */
    private record ClientConfiguration(
            String baseUrl,
            String chatCompletionsPath,
            String apiKey) {

        private static ClientConfiguration from(ModelProviderRuntime runtime) {
            return new ClientConfiguration(
                    runtime.baseUrl(), runtime.chatCompletionsPath(), runtime.apiKey());
        }
    }

    private record CachedClient(
            ClientConfiguration configuration,
            com.agent.core.llm.LlmClient client) {
    }
}
