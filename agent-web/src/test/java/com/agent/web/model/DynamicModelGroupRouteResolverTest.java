package com.agent.web.model;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.NodeExecutionContext;
import com.agent.core.llm.ChatMessage;
import com.agent.core.llm.InferenceCapability;
import com.agent.core.llm.LlmClient;
import com.agent.core.llm.TaskType;
import com.agent.web.identity.Actor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class DynamicModelGroupRouteResolverTest {
    private final LlmClient client = new LlmClient(
            RestClient.builder().baseUrl("https://model.test").build(),
            new ObjectMapper(), "/v1/chat/completions");

    @AfterEach
    void closeClient() {
        client.close();
    }

    @Test
    void isolatesUserAndUsesProviderPathWhileOrderingEndpoints() {
        UUID groupId = UUID.randomUUID();
        UUID firstEndpoint = UUID.randomUUID();
        UUID secondEndpoint = UUID.randomUUID();
        UUID firstProvider = UUID.randomUUID();
        UUID secondProvider = UUID.randomUUID();
        FakeRepository repository = new FakeRepository(
                new ModelConfigurationSnapshot(
                        List.of(),
                        List.of(
                                new ModelEndpointRecord(firstEndpoint, firstProvider, "低优先级", "model-b",
                                        Set.of(InferenceCapability.CHAT_COMPLETIONS), 2, 5, true, Instant.EPOCH, Instant.EPOCH),
                                new ModelEndpointRecord(secondEndpoint, secondProvider, "高优先级", "model-a",
                                        Set.of(InferenceCapability.CHAT_COMPLETIONS), 1, 1, true, Instant.EPOCH, Instant.EPOCH)),
                        List.of(new ModelGroupRecord(groupId, "user-a", "代码组", TaskType.CODE,
                                List.of(firstEndpoint, secondEndpoint), Instant.EPOCH, Instant.EPOCH))));
        repository.providers.put(firstProvider, new ModelProviderRuntime(
                firstProvider, "user-a", "https://gateway-a.test/base", "/custom/chat", "secret-a"));
        repository.providers.put(secondProvider, new ModelProviderRuntime(
                secondProvider, "user-a", "https://gateway-b.test/v1", "/v1/chat/completions", "secret-b"));
        List<ModelProviderRuntime> used = new java.util.ArrayList<>();
        DynamicModelGroupRouteResolver resolver = new DynamicModelGroupRouteResolver(
                repository,
                runtime -> {
                    used.add(runtime);
                    return client;
                });

        List<com.agent.core.llm.ModelEndpoint> endpoints = resolver.resolveForUser(
                "user-a", groupId.toString(), TaskType.CODE);

        assertThat(endpoints).extracting("model")
                .containsExactly("model-a", "model-b");
        assertThat(used).extracting(ModelProviderRuntime::chatCompletionsPath)
                .containsExactly("/v1/chat/completions", "/custom/chat");
        assertThat(resolver.resolveForUser(
                "user-a", groupId.toString(), TaskType.QUICK_CLASSIFICATION))
                .extracting("model").containsExactly("model-a", "model-b");
        assertThat(resolver.resolveForUser("user-b", groupId.toString(), TaskType.CODE)).isEmpty();
    }

    @Test
    void rotatesSamePriorityEndpointsAccordingToWeight() {
        UUID groupId = UUID.randomUUID();
        UUID endpointA = UUID.randomUUID();
        UUID endpointB = UUID.randomUUID();
        UUID providerA = UUID.randomUUID();
        UUID providerB = UUID.randomUUID();
        FakeRepository repository = new FakeRepository(new ModelConfigurationSnapshot(
                List.of(),
                List.of(
                        new ModelEndpointRecord(endpointA, providerA, "A", "model-a",
                                Set.of(InferenceCapability.CHAT_COMPLETIONS), 1, 2, true, Instant.EPOCH, Instant.EPOCH),
                        new ModelEndpointRecord(endpointB, providerB, "B", "model-b",
                                Set.of(InferenceCapability.CHAT_COMPLETIONS), 1, 1, true, Instant.EPOCH, Instant.EPOCH)),
                List.of(new ModelGroupRecord(groupId, "user-a", "代码组", TaskType.CODE,
                        List.of(endpointA, endpointB), Instant.EPOCH, Instant.EPOCH))));
        repository.providers.put(providerA, new ModelProviderRuntime(
                providerA, "user-a", "https://a.test", "/v1/chat/completions", "a"));
        repository.providers.put(providerB, new ModelProviderRuntime(
                providerB, "user-a", "https://b.test", "/v1/chat/completions", "b"));
        DynamicModelGroupRouteResolver resolver = new DynamicModelGroupRouteResolver(
                repository, ignored -> client);

        assertThat(resolver.resolveForUser("user-a", groupId.toString(), TaskType.CODE))
                .extracting("model").containsExactly("model-a", "model-b");
        assertThat(resolver.resolveForUser("user-a", groupId.toString(), TaskType.CODE))
                .extracting("model").containsExactly("model-a", "model-b");
        assertThat(resolver.resolveForUser("user-a", groupId.toString(), TaskType.CODE))
                .extracting("model").containsExactly("model-b", "model-a");
    }

    @Test
    void replacesCachedClientWhenRuntimeConfigurationChangesExactly() {
        UUID groupId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        FakeRepository repository = new FakeRepository(new ModelConfigurationSnapshot(
                List.of(),
                List.of(new ModelEndpointRecord(endpointId, providerId, "端点", "model-a",
                        Set.of(InferenceCapability.CHAT_COMPLETIONS), 1, 1, true, Instant.EPOCH, Instant.EPOCH)),
                List.of(new ModelGroupRecord(groupId, "user-a", "代码组", TaskType.CODE,
                        List.of(endpointId), Instant.EPOCH, Instant.EPOCH))));
        LlmClient first = mock(LlmClient.class);
        LlmClient second = mock(LlmClient.class);
        LlmClient third = mock(LlmClient.class);
        LlmClient fourth = mock(LlmClient.class);
        List<LlmClient> created = new java.util.ArrayList<>(List.of(first, second, third, fourth));
        DynamicModelGroupRouteResolver resolver = new DynamicModelGroupRouteResolver(
                repository, ignored -> created.removeFirst());

        repository.providers.put(providerId, runtime(providerId, "https://gateway.test", "/v1/chat", "Aa"));
        assertThat(resolver.resolveForUser("user-a", groupId.toString(), TaskType.CODE))
                .singleElement().extracting(com.agent.core.llm.ModelEndpoint::client).isSameAs(first);

        repository.providers.put(providerId, runtime(providerId, "https://gateway.test", "/v1/chat", "BB"));
        assertThat(resolver.resolveForUser("user-a", groupId.toString(), TaskType.CODE))
                .singleElement().extracting(com.agent.core.llm.ModelEndpoint::client).isSameAs(second);
        verify(first, times(1)).close();

        repository.providers.put(providerId, runtime(providerId, "https://new-gateway.test", "/v1/chat", "BB"));
        assertThat(resolver.resolveForUser("user-a", groupId.toString(), TaskType.CODE))
                .singleElement().extracting(com.agent.core.llm.ModelEndpoint::client).isSameAs(third);
        verify(second, times(1)).close();

        repository.providers.put(providerId, runtime(providerId, "https://new-gateway.test", "/custom/chat", "BB"));
        assertThat(resolver.resolveForUser("user-a", groupId.toString(), TaskType.CODE))
                .singleElement().extracting(com.agent.core.llm.ModelEndpoint::client).isSameAs(fourth);
        verify(third, times(1)).close();

        resolver.close();
        verify(fourth, times(1)).close();
    }

    @Test
    void keepsOldClientCachedWhenReplacementFactoryFails() {
        UUID groupId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        FakeRepository repository = new FakeRepository(new ModelConfigurationSnapshot(
                List.of(),
                List.of(new ModelEndpointRecord(endpointId, providerId, "端点", "model-a",
                        Set.of(InferenceCapability.CHAT_COMPLETIONS), 1, 1, true, Instant.EPOCH, Instant.EPOCH)),
                List.of(new ModelGroupRecord(groupId, "user-a", "代码组", TaskType.CODE,
                        List.of(endpointId), Instant.EPOCH, Instant.EPOCH))));
        LlmClient oldClient = mock(LlmClient.class);
        LlmClient newClient = mock(LlmClient.class);
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        repository.providers.put(providerId, runtime(providerId, "https://gateway.test", "/v1/chat", "old"));
        DynamicModelGroupRouteResolver resolver = new DynamicModelGroupRouteResolver(repository, runtime -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 2) {
                throw new IllegalStateException("factory unavailable");
            }
            return attempt == 1 ? oldClient : newClient;
        });
        resolver.resolveForUser("user-a", groupId.toString(), TaskType.CODE);
        repository.providers.put(providerId, runtime(providerId, "https://gateway.test", "/v1/chat", "new"));

        assertThatThrownBy(() -> resolver.resolveForUser("user-a", groupId.toString(), TaskType.CODE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("factory unavailable");
        verify(oldClient, never()).close();

        assertThat(resolver.resolveForUser("user-a", groupId.toString(), TaskType.CODE))
                .singleElement().extracting(com.agent.core.llm.ModelEndpoint::client).isSameAs(newClient);
        verify(oldClient, times(1)).close();
        resolver.close();
        verify(newClient, times(1)).close();
    }

    private ModelProviderRuntime runtime(UUID providerId, String baseUrl, String path, String apiKey) {
        return new ModelProviderRuntime(providerId, "user-a", baseUrl, path, apiKey);
    }

    private static final class FakeRepository implements ModelConfigurationRepository {
        private final ModelConfigurationSnapshot snapshot;
        private final Map<UUID, ModelProviderRuntime> providers = new java.util.HashMap<>();

        private FakeRepository(ModelConfigurationSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override public List<ModelProviderRecord> findProviders(String userId) { return snapshot.providers(); }
        @Override public List<ModelEndpointRecord> findEndpoints(String userId) { return snapshot.endpoints(); }
        @Override public List<ModelGroupRecord> findGroups(String userId) {
            return snapshot.groups().stream().filter(group -> group.ownerUserId().equals(userId)).toList();
        }
        @Override public ModelProviderRecord createProvider(UUID id, Actor actor, String name, String baseUrl, String key, Instant now) { throw new UnsupportedOperationException(); }
        @Override public ModelProviderRecord updateProvider(UUID providerId, Actor actor, String displayName, String baseUrl, String chatCompletionsPath, String apiKey, Instant now) { throw new UnsupportedOperationException(); }
        @Override public ModelEndpointRecord createEndpoint(UUID id, Actor actor, UUID providerId, String displayName, String modelId, Set<InferenceCapability> capabilities, int priority, int weight, boolean enabled, Instant now) { throw new UnsupportedOperationException(); }
        @Override public ModelEndpointRecord updateEndpoint(UUID id, Actor actor, String displayName, String modelId, Set<InferenceCapability> capabilities, int priority, int weight, boolean enabled, Instant now) { throw new UnsupportedOperationException(); }
        @Override public ModelGroupRecord createGroup(UUID id, Actor actor, String displayName, TaskType taskType, List<UUID> endpointIds, Instant now) { throw new UnsupportedOperationException(); }
        @Override public ModelGroupRecord updateGroup(UUID id, Actor actor, String displayName, TaskType taskType, List<UUID> endpointIds, Instant now) { throw new UnsupportedOperationException(); }
        @Override public void deleteProvider(UUID providerId, String userId) { throw new UnsupportedOperationException(); }
        @Override public void deleteEndpoint(UUID endpointId, String userId) { throw new UnsupportedOperationException(); }
        @Override public void deleteGroup(UUID groupId, String userId) { throw new UnsupportedOperationException(); }
        @Override public Optional<String> apiKey(UUID providerId, String userId) { return Optional.ofNullable(providers.get(providerId)).map(ModelProviderRuntime::apiKey); }
        @Override public Optional<ModelProviderRuntime> findProviderRuntime(UUID providerId, String userId) {
            return Optional.ofNullable(providers.get(providerId)).filter(value -> value.ownerUserId().equals(userId));
        }
    }
}
