package com.agent.web.model;

import com.agent.core.llm.InferenceCapability;
import com.agent.core.llm.TaskType;
import com.agent.web.identity.Actor;
import com.agent.web.identity.ActorResolver;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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

    @Test
    void preservesProviderChatCompletionsPathForRuntimeRouting() {
        FakeRepository repository = new FakeRepository();
        ModelConfigurationService service = new ModelConfigurationService(
                repository, () -> ACTOR, Clock.fixed(NOW, ZoneOffset.UTC));

        service.createProvider(
                "自定义网关", "https://gateway.example/base", "/openai/chat", "sk-secret");

        assertThat(repository.chatCompletionsPath).isEqualTo("/openai/chat");
    }

    @Test
    void updatesProviderWithCurrentActorAndPreservesOrRotatesApiKey() {
        FakeRepository repository = new FakeRepository();
        ModelConfigurationService service = new ModelConfigurationService(
                repository, () -> ACTOR, Clock.fixed(NOW, ZoneOffset.UTC));
        UUID providerId = UUID.fromString("78e56aa2-56c3-4325-a76f-97eb8c315a51");

        service.updateProvider(providerId, " 更新网关 ", "https://gateway.example/v2", "/chat", null);
        assertThat(repository.updatedOwnerId).isEqualTo(ACTOR.userId());
        assertThat(repository.updatedApiKey).isNull();
        assertThat(repository.updatedDisplayName).isEqualTo("更新网关");
        assertThat(repository.updatedBaseUrl).isEqualTo("https://gateway.example/v2");
        assertThat(repository.updatedChatCompletionsPath).isEqualTo("/chat");

        service.updateProvider(providerId, "更新网关", "https://gateway.example/v2", "/chat", " sk-rotated ");
        assertThat(repository.updatedApiKey).isEqualTo("sk-rotated");
    }

    @Test
    void rejectsInvalidProviderUpdateValues() {
        ModelConfigurationService service = new ModelConfigurationService(
                new FakeRepository(), () -> ACTOR, Clock.fixed(NOW, ZoneOffset.UTC));
        UUID providerId = UUID.fromString("80e56aa2-56c3-4325-a76f-97eb8c315a51");

        assertThatThrownBy(() -> service.updateProvider(providerId, " ", "https://gateway.example", "/chat", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateProvider(providerId, "网关", "file:///tmp", "/chat", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateProvider(providerId, "网关", "https://gateway.example", "chat", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateProvider(providerId, "网关", "https://gateway.example", "/chat", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writesSanitizedStructuredAuditLogForProviderUpdate() {
        FakeRepository repository = new FakeRepository();
        ModelConfigurationService service = new ModelConfigurationService(
                repository, () -> ACTOR, Clock.fixed(NOW, ZoneOffset.UTC));
        UUID providerId = UUID.fromString("81e56aa2-56c3-4325-a76f-97eb8c315a51");
        Logger logger = (Logger) LoggerFactory.getLogger("com.agent.audit.model-configuration");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.updateProvider(providerId, "机密网关", "https://secret.example", "/chat", "sk-rotated");

            String message = appender.list.getLast().getFormattedMessage();
            assertThat(message).contains("action=UPDATE", "userId=model-user",
                    "resourceType=PROVIDER", "resourceId=" + providerId);
            assertThat(message).doesNotContain("sk-rotated", "secret.example", "机密网关");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void updatesEndpointAndGroupWithCurrentActor() {
        FakeRepository repository = new FakeRepository();
        ModelConfigurationService service = new ModelConfigurationService(repository, () -> ACTOR, Clock.fixed(NOW, ZoneOffset.UTC));
        UUID endpointId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        service.updateEndpoint(endpointId, "端点", "model", Set.of(InferenceCapability.CHAT_COMPLETIONS), 1, 2, true);
        service.updateGroup(groupId, "组", TaskType.CODE, List.of(endpointId));
        assertThat(repository.updatedEndpointActor).isEqualTo(ACTOR);
        assertThat(repository.updatedGroupActor).isEqualTo(ACTOR);
        service.deleteEndpoint(endpointId);
        service.deleteGroup(groupId);
        assertThat(repository.deletedEndpointUser).isEqualTo(ACTOR.userId());
        assertThat(repository.deletedGroupUser).isEqualTo(ACTOR.userId());
    }

    @Test
    void rejectsInvalidEndpointAndDuplicateOrEmptyGroupValues() {
        ModelConfigurationService service = new ModelConfigurationService(new FakeRepository(), () -> ACTOR, Clock.fixed(NOW, ZoneOffset.UTC));
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.updateEndpoint(id, " ", "m", Set.of(InferenceCapability.CHAT_COMPLETIONS), 0, 1, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateEndpoint(id, "n", " ", Set.of(InferenceCapability.CHAT_COMPLETIONS), 0, 1, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateEndpoint(id, "n", "m", Set.of(), 0, 1, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateEndpoint(id, "n", "m", Set.of(InferenceCapability.CHAT_COMPLETIONS), -1, 1, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateEndpoint(id, "n", "m", Set.of(InferenceCapability.CHAT_COMPLETIONS), 0, 0, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateGroup(UUID.randomUUID(), "g", TaskType.CODE, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateGroup(UUID.randomUUID(), "g", TaskType.CODE, List.of(id, id))).hasMessageContaining("endpointIds 不能重复");
    }

    private static final class FakeRepository implements ModelConfigurationRepository {
        private String ownerId;
        private String chatCompletionsPath;
        private String updatedOwnerId;
        private String updatedDisplayName;
        private String updatedBaseUrl;
        private String updatedChatCompletionsPath;
        private String updatedApiKey;
        private Actor updatedEndpointActor;
        private Actor updatedGroupActor;
        private String deletedEndpointUser;
        private String deletedGroupUser;

        @Override public List<ModelProviderRecord> findProviders(String userId) { return List.of(); }
        @Override public List<ModelEndpointRecord> findEndpoints(String userId) { return List.of(); }
        @Override public List<ModelGroupRecord> findGroups(String userId) { return List.of(); }
        @Override public ModelProviderRecord createProvider(UUID id, Actor actor, String name, String baseUrl, String key, Instant now) {
            ownerId = actor.userId();
            return new ModelProviderRecord(id, actor.userId(), name, baseUrl,
                    ModelConfigurationService.maskApiKey(key), now, now);
        }
        @Override public ModelProviderRecord createProvider(UUID id, Actor actor, String name, String baseUrl, String path, String key, Instant now) {
            chatCompletionsPath = path;
            return createProvider(id, actor, name, baseUrl, key, now);
        }
        @Override public ModelProviderRecord updateProvider(UUID providerId, Actor actor, String displayName, String baseUrl, String chatCompletionsPath, String apiKey, Instant now) {
            updatedOwnerId = actor.userId();
            updatedDisplayName = displayName;
            updatedBaseUrl = baseUrl;
            updatedChatCompletionsPath = chatCompletionsPath;
            updatedApiKey = apiKey;
            return new ModelProviderRecord(providerId, actor.userId(), displayName, baseUrl,
                    chatCompletionsPath, ModelConfigurationService.maskApiKey(apiKey == null ? "sk-existing" : apiKey), now, now);
        }
        @Override public ModelEndpointRecord createEndpoint(UUID id, Actor actor, UUID providerId, String displayName, String modelId, Set<InferenceCapability> capabilities, int priority, int weight, boolean enabled, Instant now) { throw new UnsupportedOperationException(); }
        @Override public ModelGroupRecord createGroup(UUID id, Actor actor, String displayName, TaskType taskType, List<UUID> endpointIds, Instant now) { throw new UnsupportedOperationException(); }
        @Override public ModelEndpointRecord updateEndpoint(UUID id, Actor actor, String n, String m, Set<InferenceCapability> c, int p, int w, boolean e, Instant now) { updatedEndpointActor = actor; return new ModelEndpointRecord(id, UUID.randomUUID(), n, m, c, p, w, e, now, now); }
        @Override public ModelGroupRecord updateGroup(UUID id, Actor actor, String n, TaskType t, List<UUID> endpoints, Instant now) { updatedGroupActor = actor; return new ModelGroupRecord(id, actor.userId(), n, t, endpoints, now, now); }
        @Override public void deleteEndpoint(UUID id, String userId) { deletedEndpointUser = userId; }
        @Override public void deleteGroup(UUID id, String userId) { deletedGroupUser = userId; }
        @Override public void deleteProvider(UUID providerId, String userId) { throw new UnsupportedOperationException(); }
        @Override public Optional<String> apiKey(UUID providerId, String userId) { return Optional.empty(); }
    }
}
