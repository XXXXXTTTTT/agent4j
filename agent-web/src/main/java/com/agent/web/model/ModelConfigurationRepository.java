package com.agent.web.model;

import com.agent.core.llm.InferenceCapability;
import com.agent.core.llm.TaskType;
import com.agent.web.identity.Actor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 模型配置持久化端口。 */
public interface ModelConfigurationRepository {
    List<ModelProviderRecord> findProviders(String userId);
    List<ModelEndpointRecord> findEndpoints(String userId);
    List<ModelGroupRecord> findGroups(String userId);
    ModelProviderRecord createProvider(UUID providerId, Actor actor, String displayName,
                                       String baseUrl, String apiKey, Instant now);
    /** 使用精确 Chat Completions 路径创建 Provider。 */
    default ModelProviderRecord createProvider(UUID providerId, Actor actor, String displayName,
                                               String baseUrl, String chatCompletionsPath,
                                               String apiKey, Instant now) {
        return createProvider(providerId, actor, displayName, baseUrl, apiKey, now);
    }
    ModelProviderRecord updateProvider(UUID providerId, Actor actor, String displayName,
                                       String baseUrl, String chatCompletionsPath,
                                       String apiKey, Instant now);
    ModelEndpointRecord createEndpoint(UUID endpointId, Actor actor, UUID providerId,
                                       String displayName, String modelId,
                                       Set<InferenceCapability> capabilities,
                                       int priority, int weight, boolean enabled, Instant now);
    ModelGroupRecord createGroup(UUID groupId, Actor actor, String displayName,
                                 TaskType taskType, List<UUID> endpointIds, Instant now);
    void deleteProvider(UUID providerId, String userId);
    Optional<String> apiKey(UUID providerId, String userId);

    /** 读取当前用户的 Provider 私密运行时配置。 */
    default Optional<ModelProviderRuntime> findProviderRuntime(UUID providerId, String userId) {
        return Optional.empty();
    }
}
