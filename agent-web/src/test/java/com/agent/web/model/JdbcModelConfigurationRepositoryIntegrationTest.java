package com.agent.web.model;

import com.agent.core.llm.InferenceCapability;
import com.agent.core.llm.TaskType;
import com.agent.web.identity.Actor;
import com.agent.web.persistence.JdbcModelConfigurationRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcModelConfigurationRepositoryIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcClient jdbc;
    private DataSource dataSource;
    private JdbcModelConfigurationRepository repository;

    @BeforeAll
    static void startPostgres() {
        boolean dockerAvailable;
        try {
            dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException exception) {
            Assumptions.assumeTrue(false, "Docker Engine 不可用: " + exception.getMessage());
            return;
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker Engine 不可用");
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        if (POSTGRES.isRunning()) POSTGRES.stop();
    }

    @BeforeEach
    void setUp() {
        dataSource = dataSource();
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("truncate table agent_model_group_endpoints, agent_model_groups, agent_model_endpoints, agent_model_providers, agent_users cascade").update();
        repository = new JdbcModelConfigurationRepository(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @Test
    void updatesProviderWithoutExposingSecretAndPreservesOrRotatesApiKey() {
        Actor owner = new Actor("provider-owner", "Provider Owner");
        insertUser(owner);
        UUID providerId = UUID.fromString("91e56aa2-56c3-4325-a76f-97eb8c315a51");
        repository.createProvider(providerId, owner, "原网关", "https://old.example", "/v1/chat/completions", "sk-existing", NOW);

        ModelProviderRecord preserved = repository.updateProvider(providerId, owner, "保留密钥", "https://new.example", "/chat", null, NOW.plusSeconds(1));
        assertThat(repository.apiKey(providerId, owner.userId())).contains("sk-existing");
        assertThat(preserved.apiKeyMasked()).isEqualTo("sk-e****ting");
        assertThat(preserved.apiKeyMasked()).doesNotContain("sk-existing");

        ModelProviderRecord rotated = repository.updateProvider(providerId, owner, "轮换密钥", "https://new.example", "/chat", "sk-rotated", NOW.plusSeconds(2));
        assertThat(repository.apiKey(providerId, owner.userId())).contains("sk-rotated");
        assertThat(rotated.apiKeyMasked()).isEqualTo("sk-r****ated");
        assertThat(rotated.apiKeyMasked()).doesNotContain("sk-rotated");
    }

    @Test
    void rejectsUpdateByAnotherUser() {
        Actor owner = new Actor("provider-owner", "Provider Owner");
        Actor other = new Actor("provider-other", "Provider Other");
        insertUser(owner);
        insertUser(other);
        UUID providerId = UUID.fromString("92e56aa2-56c3-4325-a76f-97eb8c315a51");
        repository.createProvider(providerId, owner, "原网关", "https://old.example", "sk-existing", NOW);

        assertThatThrownBy(() -> repository.updateProvider(providerId, other, "越权", "https://new.example", "/chat", null, NOW))
                .isInstanceOf(JdbcModelConfigurationRepository.ModelConfigurationNotFoundException.class);
    }

    @Test
    void rejectsDeletingProviderWithAnyEndpoint() {
        Actor owner = new Actor("provider-owner", "Provider Owner");
        insertUser(owner);
        UUID providerId = UUID.fromString("93e56aa2-56c3-4325-a76f-97eb8c315a51");
        repository.createProvider(providerId, owner, "原网关", "https://old.example", "sk-existing", NOW);
        repository.createEndpoint(UUID.fromString("94e56aa2-56c3-4325-a76f-97eb8c315a51"), owner, providerId,
                "未入组端点", "model", Set.of(InferenceCapability.CHAT_COMPLETIONS), 0, 1, true, NOW);

        assertThatThrownBy(() -> repository.deleteProvider(providerId, owner.userId()))
                .isInstanceOf(JdbcModelConfigurationRepository.ModelConfigurationConflictException.class)
                .hasMessage("Provider 仍有 Endpoint，请先删除 Endpoint: " + providerId);
    }

    @Test
    void serializesDeletionAfterProviderLockAndRejectsEndpointCreatedBeforeUnlock() throws Exception {
        Actor owner = new Actor("provider-lock-owner", "Provider Lock Owner");
        insertUser(owner);
        UUID providerId = UUID.fromString("95e56aa2-56c3-4325-a76f-97eb8c315a51");
        UUID endpointId = UUID.fromString("96e56aa2-56c3-4325-a76f-97eb8c315a51");
        repository.createProvider(providerId, owner, "锁定网关", "https://locked.example", "sk-existing", NOW);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement lock = connection.prepareStatement(
                    "select provider_id from agent_model_providers where provider_id = ? for update")) {
                lock.setObject(1, providerId);
                lock.executeQuery().close();
            }
            try (PreparedStatement endpoint = connection.prepareStatement("""
                    insert into agent_model_endpoints (
                        endpoint_id, provider_id, display_name, model_id, capabilities,
                        priority, weight, enabled, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                endpoint.setObject(1, endpointId);
                endpoint.setObject(2, providerId);
                endpoint.setString(3, "锁定端点");
                endpoint.setString(4, "model");
                endpoint.setArray(5, connection.createArrayOf("text", new String[]{"CHAT_COMPLETIONS"}));
                endpoint.setInt(6, 0);
                endpoint.setInt(7, 1);
                endpoint.setBoolean(8, true);
                endpoint.setTimestamp(9, java.sql.Timestamp.from(NOW));
                endpoint.setTimestamp(10, java.sql.Timestamp.from(NOW));
                endpoint.executeUpdate();
            }

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Throwable> deletion = executor.submit(() -> {
                    try {
                        repository.deleteProvider(providerId, owner.userId());
                        return null;
                    } catch (Throwable failure) {
                        return failure;
                    }
                });
                Thread.sleep(200);
                assertThat(deletion.isDone()).isFalse();
                connection.commit();
                Throwable failure = deletion.get(5, TimeUnit.SECONDS);
                assertThat(failure)
                        .isInstanceOf(JdbcModelConfigurationRepository.ModelConfigurationConflictException.class)
                        .hasMessage("Provider 仍有 Endpoint，请先删除 Endpoint: " + providerId);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void updatesEndpointPreservingProviderAndRejectsCrossUserUpdateDelete() {
        Actor owner = new Actor("endpoint-owner", "Endpoint Owner");
        Actor other = new Actor("endpoint-other", "Endpoint Other");
        insertUser(owner); insertUser(other);
        UUID providerId = UUID.randomUUID(); UUID endpointId = UUID.randomUUID();
        repository.createProvider(providerId, owner, "p", "https://p", "k", NOW);
        repository.createEndpoint(endpointId, owner, providerId, "e", "m", Set.of(InferenceCapability.CHAT_COMPLETIONS), 0, 1, true, NOW);
        assertThatThrownBy(() -> repository.updateEndpoint(endpointId, other, "x", "x", Set.of(InferenceCapability.STREAMING), 1, 2, false, NOW))
                .isInstanceOf(JdbcModelConfigurationRepository.ModelConfigurationNotFoundException.class);
        assertThatThrownBy(() -> repository.deleteEndpoint(endpointId, other.userId()))
                .isInstanceOf(JdbcModelConfigurationRepository.ModelConfigurationNotFoundException.class);
        ModelEndpointRecord updated = repository.updateEndpoint(endpointId, owner, "x", "x", Set.of(InferenceCapability.STREAMING), 1, 2, false, NOW.plusSeconds(1));
        assertThat(updated.providerId()).isEqualTo(providerId);
    }

    @Test
    void updatesGroupInOrderAndRollsBackOnForeignOrMissingEndpoint() {
        Actor owner = new Actor("group-owner", "Group Owner"); Actor other = new Actor("group-other", "Group Other");
        insertUser(owner); insertUser(other);
        UUID provider = UUID.randomUUID(); UUID foreignProvider = UUID.randomUUID();
        repository.createProvider(provider, owner, "p", "https://p", "k", NOW);
        repository.createProvider(foreignProvider, other, "q", "https://q", "k", NOW);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), foreign = UUID.randomUUID();
        repository.createEndpoint(a, owner, provider, "a", "a", Set.of(InferenceCapability.CHAT_COMPLETIONS), 0, 1, true, NOW);
        repository.createEndpoint(b, owner, provider, "b", "b", Set.of(InferenceCapability.CHAT_COMPLETIONS), 0, 1, true, NOW);
        repository.createEndpoint(foreign, other, foreignProvider, "f", "f", Set.of(InferenceCapability.CHAT_COMPLETIONS), 0, 1, true, NOW);
        UUID groupId = UUID.randomUUID(); repository.createGroup(groupId, owner, "g", TaskType.CODE, List.of(a, b), NOW);
        assertThat(repository.updateGroup(groupId, owner, "g2", TaskType.CODE, List.of(b, a), NOW.plusSeconds(1)).endpointIds()).containsExactly(b, a);
        assertThatThrownBy(() -> repository.updateGroup(groupId, other, "x", TaskType.CODE, List.of(a), NOW)).isInstanceOf(JdbcModelConfigurationRepository.ModelConfigurationNotFoundException.class);
        assertThatThrownBy(() -> repository.deleteGroup(groupId, other.userId())).isInstanceOf(JdbcModelConfigurationRepository.ModelConfigurationNotFoundException.class);
        assertThatThrownBy(() -> repository.updateGroup(groupId, owner, "bad", TaskType.CODE, List.of(foreign), NOW.plusSeconds(2))).isInstanceOf(JdbcModelConfigurationRepository.ModelConfigurationNotFoundException.class);
        assertThat(repository.findGroups(owner.userId()).getFirst().endpointIds()).containsExactly(b, a);
        UUID missing = UUID.randomUUID();
        assertThatThrownBy(() -> repository.updateGroup(groupId, owner, "bad", TaskType.CODE, List.of(missing), NOW.plusSeconds(2))).isInstanceOf(JdbcModelConfigurationRepository.ModelConfigurationNotFoundException.class);
        assertThat(repository.findGroups(owner.userId()).getFirst().endpointIds()).containsExactly(b, a);
    }

    @Test
    void rejectsReferencedEndpointDeleteAndGroupDeleteKeepsEndpointProvider() {
        Actor owner = new Actor("delete-owner", "Delete Owner"); insertUser(owner);
        UUID provider = UUID.randomUUID(), endpoint = UUID.randomUUID(), group = UUID.randomUUID();
        repository.createProvider(provider, owner, "p", "https://p", "k", NOW);
        repository.createEndpoint(endpoint, owner, provider, "e", "m", Set.of(InferenceCapability.CHAT_COMPLETIONS), 0, 1, true, NOW);
        repository.createGroup(group, owner, "g", TaskType.CODE, List.of(endpoint), NOW);
        assertThatThrownBy(() -> repository.deleteEndpoint(endpoint, owner.userId())).isInstanceOf(JdbcModelConfigurationRepository.ModelConfigurationConflictException.class)
                .hasMessage("Endpoint 仍被模型组引用，请先从 Group 移除: " + endpoint);
        repository.deleteGroup(group, owner.userId());
        assertThat(repository.findGroups(owner.userId())).isEmpty();
        assertThat(repository.findEndpoints(owner.userId())).extracting(ModelEndpointRecord::endpointId).contains(endpoint);
        assertThat(repository.findProviders(owner.userId())).extracting(ModelProviderRecord::providerId).contains(provider);
    }

    @Test
    void rollsBackGroupAndMembershipWhenDuplicateMembershipInsertFails() {
        Actor owner = new Actor("rollback-owner", "Rollback Owner");
        insertUser(owner);
        UUID provider = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID(), group = UUID.randomUUID();
        repository.createProvider(provider, owner, "p", "https://p", "k", NOW);
        repository.createEndpoint(first, owner, provider, "first", "m1", Set.of(InferenceCapability.CHAT_COMPLETIONS), 0, 1, true, NOW);
        repository.createEndpoint(second, owner, provider, "second", "m2", Set.of(InferenceCapability.CHAT_COMPLETIONS), 0, 1, true, NOW);
        repository.createGroup(group, owner, "original", TaskType.CODE, List.of(first, second), NOW);

        assertThatThrownBy(() -> repository.updateGroup(group, owner, "should-rollback", TaskType.VISION,
                List.of(first, first), NOW.plusSeconds(1))).isInstanceOf(RuntimeException.class);

        ModelGroupRecord restored = repository.findGroups(owner.userId()).getFirst();
        assertThat(restored.displayName()).isEqualTo("original");
        assertThat(restored.taskType()).isEqualTo(TaskType.CODE);
        assertThat(restored.endpointIds()).containsExactly(first, second);
    }

    private void insertUser(Actor actor) {
        jdbc.sql("insert into agent_users (user_id, display_name, enabled, created_at, updated_at) values (:userId, :displayName, true, :now, :now)")
                .param("userId", actor.userId()).param("displayName", actor.displayName())
                .param("now", java.sql.Timestamp.from(NOW)).update();
    }

    private DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(POSTGRES.getDriverClassName());
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}
