package com.agent.web.model;

import com.agent.core.llm.InferenceCapability;
import com.agent.core.llm.TaskType;
import com.agent.web.identity.Actor;
import com.agent.web.identity.ActorResolver;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelConfigurationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");
    private static final Actor ACTOR = new Actor("model-user", "模型用户");

    @Test
    void masksApiKeysAndKeepsUserConfigurationIsolated() {
        assertThat(ModelConfigurationService.maskApiKey("sk-1234567890")).isEqualTo("sk-1****7890");
        assertThat(ModelConfigurationService.maskApiKey("short")).isEqualTo("****");
        FakeRepository repository = new FakeRepository();
        ModelConfigurationService service = new ModelConfigurationService(
                repository, () -> ACTOR, Clock.fixed(NOW, ZoneOffset.UTC));

        ModelProviderRecord provider = service.createProvider(
                "主网关", "https://gateway.example/v1", "sk-1234567890");

        assertThat(provider.apiKeyMasked()).isEqualTo("sk-1****7890");
        assertThat(repository.ownerId).isEqualTo(ACTOR.userId());
        assertThatThrownBy(() -> service.createProvider("非法", "file:///tmp", "key"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class FakeRepository implements ModelConfigurationRepository {
        private String ownerId;

        @Override public List<ModelProviderRecord> findProviders(String userId) { return List.of(); }
        @Override public List<ModelEndpointRecord> findEndpoints(String userId) { return List.of(); }
        @Override public List<ModelGroupRecord> findGroups(String userId) { return List.of(); }
        @Override public ModelProviderRecord createProvider(UUID id, Actor actor, String name, String baseUrl, String key, Instant now) {
            ownerId = actor.userId();
            return new ModelProviderRecord(id, actor.userId(), name, baseUrl,
                    ModelConfigurationService.maskApiKey(key), now, now);
        }
        @Override public ModelEndpointRecord createEndpoint(UUID id, Actor actor, UUID providerId, String displayName, String modelId, Set<InferenceCapability> capabilities, int priority, int weight, boolean enabled, Instant now) { throw new UnsupportedOperationException(); }
        @Override public ModelGroupRecord createGroup(UUID id, Actor actor, String displayName, TaskType taskType, List<UUID> endpointIds, Instant now) { throw new UnsupportedOperationException(); }
        @Override public void deleteProvider(UUID providerId, String userId) { throw new UnsupportedOperationException(); }
        @Override public Optional<String> apiKey(UUID providerId, String userId) { return Optional.empty(); }
    }
}
