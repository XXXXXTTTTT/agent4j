package com.agent.web.model;

import com.agent.core.llm.InferenceCapability;
import com.agent.core.llm.OpenAiEndpoint;
import com.agent.core.llm.TaskType;
import com.agent.web.identity.Actor;
import com.agent.web.identity.ActorResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 用户隔离的模型 Provider、端点和模型组应用服务。 */
public final class ModelConfigurationService {
    private static final Logger AUDIT = LoggerFactory.getLogger("com.agent.audit.model-configuration");
    private final ModelConfigurationRepository repository;
    private final ActorResolver actorResolver;
    private final Clock clock;

    public ModelConfigurationService(ModelConfigurationRepository repository,
                                     ActorResolver actorResolver, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.actorResolver = Objects.requireNonNull(actorResolver, "actorResolver 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    public ModelConfigurationSnapshot snapshot() {
        String userId = actorResolver.current().userId();
        return new ModelConfigurationSnapshot(
                repository.findProviders(userId),
                repository.findEndpoints(userId),
                repository.findGroups(userId));
    }

    public ModelProviderRecord createProvider(String displayName, String baseUrl, String apiKey) {
        return createProvider(displayName, baseUrl, "/v1/chat/completions", apiKey);
    }

    /** 创建使用精确 Chat Completions 路径的 Provider。 */
    public ModelProviderRecord createProvider(
            String displayName, String baseUrl, String chatCompletionsPath, String apiKey) {
        requireText(displayName, "displayName");
        requireText(apiKey, "apiKey");
        String exactPath = normalizeChatCompletionsPath(chatCompletionsPath);
        URI uri;
        try {
            uri = URI.create(Objects.requireNonNull(baseUrl, "baseUrl 不能为空").trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("baseUrl 必须是有效 URI", exception);
        }
        if (!uri.isAbsolute() || !("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("baseUrl 必须是 HTTP/HTTPS URI");
        }
        Actor actor = actorResolver.current();
        UUID providerId = UUID.randomUUID();
        ModelProviderRecord provider = repository.createProvider(providerId, actor, displayName.trim(),
                uri.toString(), exactPath, apiKey.trim(), clock.instant());
        auditProvider("CREATE", actor, provider.providerId());
        return provider;
    }

    /** 更新当前用户的 Provider；空 API Key 表示保留既有密钥。 */
    public ModelProviderRecord updateProvider(UUID providerId, String displayName, String baseUrl,
                                              String chatCompletionsPath, String apiKey) {
        Objects.requireNonNull(providerId, "providerId 不能为空");
        requireText(displayName, "displayName");
        String exactPath = requireChatCompletionsPath(chatCompletionsPath);
        URI uri = requireHttpUri(baseUrl);
        if (apiKey != null) {
            requireText(apiKey, "apiKey");
        }
        Actor actor = actorResolver.current();
        ModelProviderRecord provider = repository.updateProvider(providerId, actor, displayName.trim(),
                uri.toString(), exactPath, apiKey == null ? null : apiKey.trim(), clock.instant());
        auditProvider("UPDATE", actor, provider.providerId());
        return provider;
    }

    public ModelEndpointRecord createEndpoint(UUID providerId, String displayName, String modelId,
                                              Set<InferenceCapability> capabilities,
                                              int priority, int weight, boolean enabled) {
        Objects.requireNonNull(providerId, "providerId 不能为空");
        requireText(displayName, "displayName");
        requireText(modelId, "modelId");
        if (capabilities == null || capabilities.isEmpty()) {
            throw new IllegalArgumentException("capabilities 不能为空");
        }
        if (priority < 0 || weight <= 0) {
            throw new IllegalArgumentException("priority 必须大于等于 0，weight 必须大于 0");
        }
        Actor actor = actorResolver.current();
        ModelEndpointRecord endpoint = repository.createEndpoint(UUID.randomUUID(), actor, providerId,
                displayName.trim(), modelId.trim(), Set.copyOf(capabilities), priority, weight,
                enabled, clock.instant());
        auditResource("CREATE", actor, "ENDPOINT", endpoint.endpointId());
        return endpoint;
    }

    public ModelGroupRecord createGroup(String displayName, TaskType taskType, List<UUID> endpointIds) {
        requireText(displayName, "displayName");
        Objects.requireNonNull(taskType, "taskType 不能为空");
        if (endpointIds == null || endpointIds.isEmpty()) {
            throw new IllegalArgumentException("endpointIds 不能为空");
        }
        validateEndpointIds(endpointIds);
        Actor actor = actorResolver.current();
        ModelGroupRecord group = repository.createGroup(UUID.randomUUID(), actor, displayName.trim(),
                taskType, List.copyOf(endpointIds), clock.instant());
        auditResource("CREATE", actor, "GROUP", group.groupId());
        return group;
    }

    public ModelEndpointRecord updateEndpoint(UUID endpointId, String displayName, String modelId,
                                              Set<InferenceCapability> capabilities, int priority,
                                              int weight, boolean enabled) {
        Objects.requireNonNull(endpointId, "endpointId 不能为空");
        validateEndpoint(displayName, modelId, capabilities, priority, weight);
        Actor actor = actorResolver.current();
        ModelEndpointRecord endpoint = repository.updateEndpoint(endpointId, actor, displayName.trim(), modelId.trim(),
                Set.copyOf(capabilities), priority, weight, enabled, clock.instant());
        auditResource("UPDATE", actor, "ENDPOINT", endpoint.endpointId());
        return endpoint;
    }

    public ModelGroupRecord updateGroup(UUID groupId, String displayName, TaskType taskType, List<UUID> endpointIds) {
        Objects.requireNonNull(groupId, "groupId 不能为空");
        requireText(displayName, "displayName");
        Objects.requireNonNull(taskType, "taskType 不能为空");
        validateEndpointIds(endpointIds);
        Actor actor = actorResolver.current();
        ModelGroupRecord group = repository.updateGroup(groupId, actor, displayName.trim(), taskType,
                List.copyOf(endpointIds), clock.instant());
        auditResource("UPDATE", actor, "GROUP", group.groupId());
        return group;
    }

    public void deleteProvider(UUID providerId) {
        Objects.requireNonNull(providerId, "providerId 不能为空");
        Actor actor = actorResolver.current();
        repository.deleteProvider(providerId, actor.userId());
        auditProvider("DELETE", actor, providerId);
    }

    public void deleteEndpoint(UUID endpointId) {
        Objects.requireNonNull(endpointId, "endpointId 不能为空");
        Actor actor = actorResolver.current();
        repository.deleteEndpoint(endpointId, actor.userId());
        auditResource("DELETE", actor, "ENDPOINT", endpointId);
    }

    public void deleteGroup(UUID groupId) {
        Objects.requireNonNull(groupId, "groupId 不能为空");
        Actor actor = actorResolver.current();
        repository.deleteGroup(groupId, actor.userId());
        auditResource("DELETE", actor, "GROUP", groupId);
    }

    public static String maskApiKey(String apiKey) {
        requireText(apiKey, "apiKey");
        String value = apiKey.trim();
        if (value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " 不能为空").isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
    }

    private static void validateEndpoint(String displayName, String modelId, Set<InferenceCapability> capabilities,
                                         int priority, int weight) {
        requireText(displayName, "displayName");
        requireText(modelId, "modelId");
        if (capabilities == null || capabilities.isEmpty()) throw new IllegalArgumentException("capabilities 不能为空");
        if (priority < 0 || weight <= 0) throw new IllegalArgumentException("priority 必须大于等于 0，weight 必须大于 0");
    }

    private static void validateEndpointIds(List<UUID> endpointIds) {
        if (endpointIds == null || endpointIds.isEmpty()) throw new IllegalArgumentException("endpointIds 不能为空");
        if (endpointIds.stream().distinct().count() != endpointIds.size()) throw new IllegalArgumentException("endpointIds 不能重复");
    }

    private static void auditResource(String action, Actor actor, String type, UUID id) {
        AUDIT.info("action={} userId={} resourceType={} resourceId={}", action, actor.userId(), type, id);
    }

    private static String normalizeChatCompletionsPath(String value) {
        String path = value == null || value.isBlank()
                ? "/v1/chat/completions" : value.trim();
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("chatCompletionsPath 必须以 / 开头");
        }
        OpenAiEndpoint.resolve("https://model-provider.invalid", path);
        return path;
    }

    private static String requireChatCompletionsPath(String value) {
        requireText(value, "chatCompletionsPath");
        String path = value.trim();
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("chatCompletionsPath 必须以 / 开头");
        }
        OpenAiEndpoint.resolve("https://model-provider.invalid", path);
        return path;
    }

    private static URI requireHttpUri(String baseUrl) {
        URI uri;
        try {
            uri = URI.create(Objects.requireNonNull(baseUrl, "baseUrl 不能为空").trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("baseUrl 必须是有效 URI", exception);
        }
        if (!uri.isAbsolute() || !("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("baseUrl 必须是 HTTP/HTTPS URI");
        }
        return uri;
    }

    private static void auditProvider(String action, Actor actor, UUID providerId) {
        AUDIT.info("action={} userId={} resourceType=PROVIDER resourceId={}",
                action, actor.userId(), providerId);
    }
}
