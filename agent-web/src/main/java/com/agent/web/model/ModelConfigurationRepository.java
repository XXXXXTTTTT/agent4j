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
    ModelEndpointRecord createEndpoint(UUID endpointId, Actor actor, UUID providerId,
                                       String displayName, String modelId,
                                       Set<InferenceCapability> capabilities,
                                       int priority, int weight, boolean enabled, Instant now);
    ModelGroupRecord createGroup(UUID groupId, Actor actor, String displayName,
                                 TaskType taskType, List<UUID> endpointIds, Instant now);
    void deleteProvider(UUID providerId, String userId);
    Optional<String> apiKey(UUID providerId, String userId);
}
