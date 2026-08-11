package com.agent.web.persistence;

import com.agent.core.llm.InferenceCapability;
import com.agent.core.llm.TaskType;
import com.agent.web.identity.Actor;
import com.agent.web.model.ModelConfigurationRepository;
import com.agent.web.model.ModelEndpointRecord;
import com.agent.web.model.ModelGroupRecord;
import com.agent.web.model.ModelProviderRecord;
import com.agent.web.model.ModelConfigurationService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** PostgreSQL 模型配置仓储，所有读取均带当前用户隔离条件。 */
public final class JdbcModelConfigurationRepository implements ModelConfigurationRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcModelConfigurationRepository(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不能为空");
        this.transactions = Objects.requireNonNull(transactions, "transactions 不能为空");
    }

    @Override
    public List<ModelProviderRecord> findProviders(String userId) {
        return jdbc.sql("""
                select provider_id, owner_user_id, display_name, base_url, api_key,
                       created_at, updated_at
                from agent_model_providers
                where owner_user_id = :userId
                order by display_name, provider_id
                """).param("userId", userId).query((rs, n) -> new ModelProviderRecord(
                rs.getObject("provider_id", UUID.class), rs.getString("owner_user_id"),
                rs.getString("display_name"), rs.getString("base_url"),
                ModelConfigurationService.maskApiKey(rs.getString("api_key")),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()))
                .list();
    }

    @Override
    public List<ModelEndpointRecord> findEndpoints(String userId) {
        List<ModelEndpointRecord> endpoints = jdbc.sql("""
                select endpoint.endpoint_id, endpoint.provider_id, endpoint.display_name,
                       endpoint.model_id, endpoint.capabilities, endpoint.priority,
                       endpoint.weight, endpoint.enabled, endpoint.created_at, endpoint.updated_at
                from agent_model_endpoints endpoint
                join agent_model_providers provider on provider.provider_id = endpoint.provider_id
                where provider.owner_user_id = :userId
                order by endpoint.priority, endpoint.endpoint_id
                """).param("userId", userId).query((rs, n) -> new ModelEndpointRecord(
                rs.getObject("endpoint_id", UUID.class), rs.getObject("provider_id", UUID.class),
                rs.getString("display_name"), rs.getString("model_id"),
                parseCapabilities(rs.getArray("capabilities")), rs.getInt("priority"),
                rs.getInt("weight"), rs.getBoolean("enabled"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()))
                .list();
        return List.copyOf(endpoints);
    }

    @Override
    public List<ModelGroupRecord> findGroups(String userId) {
        List<GroupRow> groups = jdbc.sql("""
                select group_config.group_id, group_config.owner_user_id, group_config.display_name,
                       group_config.task_type, group_config.created_at, group_config.updated_at,
                       membership.endpoint_id, membership.position
                from agent_model_groups group_config
                left join agent_model_group_endpoints membership on membership.group_id = group_config.group_id
                where group_config.owner_user_id = :userId
                order by group_config.display_name, group_config.group_id, membership.position
                """).param("userId", userId).query((rs, n) -> new GroupRow(
                rs.getObject("group_id", UUID.class), rs.getString("owner_user_id"),
                rs.getString("display_name"), TaskType.valueOf(rs.getString("task_type")),
                rs.getObject("endpoint_id", UUID.class), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant())).list();
        List<ModelGroupRecord> result = new ArrayList<>();
        for (GroupRow row : groups) {
            if (!result.isEmpty() && result.getLast().groupId().equals(row.groupId())) {
                ModelGroupRecord previous = result.removeLast();
                List<UUID> ids = new ArrayList<>(previous.endpointIds());
                if (row.endpointId() != null) ids.add(row.endpointId());
                result.add(new ModelGroupRecord(previous.groupId(), previous.ownerUserId(),
                        previous.displayName(), previous.taskType(), ids,
                        previous.createdAt(), previous.updatedAt()));
            } else {
                result.add(new ModelGroupRecord(row.groupId(), row.ownerUserId(), row.displayName(),
                        row.taskType(), row.endpointId() == null ? List.of() : List.of(row.endpointId()),
                        row.createdAt(), row.updatedAt()));
            }
        }
        return List.copyOf(result);
    }

    @Override
    public ModelProviderRecord createProvider(UUID providerId, Actor actor, String displayName,
                                              String baseUrl, String apiKey, Instant now) {
        jdbc.sql("""
                insert into agent_model_providers
                    (provider_id, owner_user_id, display_name, base_url, api_key, created_at, updated_at)
                values (:providerId, :ownerUserId, :displayName, :baseUrl, :apiKey, :createdAt, :updatedAt)
                """).param("providerId", providerId).param("ownerUserId", actor.userId())
                .param("displayName", displayName).param("baseUrl", baseUrl).param("apiKey", apiKey)
                .param("createdAt", timestamp(now)).param("updatedAt", timestamp(now)).update();
        return findProviders(actor.userId()).stream().filter(value -> value.providerId().equals(providerId))
                .findFirst().orElseThrow();
    }

    @Override
    public ModelEndpointRecord createEndpoint(UUID endpointId, Actor actor, UUID providerId,
                                              String displayName, String modelId,
                                              Set<InferenceCapability> capabilities, int priority,
                                              int weight, boolean enabled, Instant now) {
        requireOwnedProvider(providerId, actor.userId());
        jdbc.sql("""
                insert into agent_model_endpoints
                    (endpoint_id, provider_id, display_name, model_id, capabilities,
                     priority, weight, enabled, created_at, updated_at)
                values (:endpointId, :providerId, :displayName, :modelId, :capabilities,
                        :priority, :weight, :enabled, :createdAt, :updatedAt)
                """).param("endpointId", endpointId).param("providerId", providerId)
                .param("displayName", displayName).param("modelId", modelId)
                .param("capabilities", capabilities.stream().map(Enum::name).toArray(String[]::new))
                .param("priority", priority).param("weight", weight).param("enabled", enabled)
                .param("createdAt", timestamp(now)).param("updatedAt", timestamp(now)).update();
        return findEndpoints(actor.userId()).stream().filter(value -> value.endpointId().equals(endpointId))
                .findFirst().orElseThrow();
    }

    @Override
    public ModelGroupRecord createGroup(UUID groupId, Actor actor, String displayName, TaskType taskType,
                                        List<UUID> endpointIds, Instant now) {
        return Objects.requireNonNull(transactions.execute(status -> {
            for (UUID endpointId : endpointIds) requireOwnedEndpoint(endpointId, actor.userId());
            jdbc.sql("""
                    insert into agent_model_groups
                        (group_id, owner_user_id, display_name, task_type, created_at, updated_at)
                    values (:groupId, :ownerUserId, :displayName, :taskType, :createdAt, :updatedAt)
                    """).param("groupId", groupId).param("ownerUserId", actor.userId())
                    .param("displayName", displayName).param("taskType", taskType.name())
                    .param("createdAt", timestamp(now)).param("updatedAt", timestamp(now)).update();
            for (int i = 0; i < endpointIds.size(); i++) {
                jdbc.sql("""
                        insert into agent_model_group_endpoints (group_id, endpoint_id, position)
                        values (:groupId, :endpointId, :position)
                        """).param("groupId", groupId).param("endpointId", endpointIds.get(i))
                        .param("position", i).update();
            }
            return findGroups(actor.userId()).stream().filter(value -> value.groupId().equals(groupId))
                    .findFirst().orElseThrow();
        }), "模型组事务返回值不能为空");
    }

    @Override
    public void deleteProvider(UUID providerId, String userId) {
        requireOwnedProvider(providerId, userId);
        Long references = jdbc.sql("""
                select count(*) from agent_model_endpoints endpoint
                join agent_model_group_endpoints membership on membership.endpoint_id = endpoint.endpoint_id
                where endpoint.provider_id = :providerId
                """).param("providerId", providerId).query(Long.class).single();
        if (references != 0) throw new ModelConfigurationConflictException("Provider 仍被模型组引用: " + providerId);
        jdbc.sql("delete from agent_model_providers where provider_id = :providerId")
                .param("providerId", providerId).update();
    }

    @Override
    public Optional<String> apiKey(UUID providerId, String userId) {
        return jdbc.sql("select api_key from agent_model_providers where provider_id = :providerId and owner_user_id = :userId")
                .param("providerId", providerId).param("userId", userId).query(String.class).optional();
    }

    private void requireOwnedProvider(UUID providerId, String userId) {
        if (jdbc.sql("select count(*) from agent_model_providers where provider_id = :providerId and owner_user_id = :userId")
                .param("providerId", providerId).param("userId", userId).query(Long.class).single() == 0) {
            throw new ModelConfigurationNotFoundException(providerId);
        }
    }

    private void requireOwnedEndpoint(UUID endpointId, String userId) {
        if (jdbc.sql("""
                select count(*) from agent_model_endpoints endpoint
                join agent_model_providers provider on provider.provider_id = endpoint.provider_id
                where endpoint.endpoint_id = :endpointId and provider.owner_user_id = :userId
                """).param("endpointId", endpointId).param("userId", userId).query(Long.class).single() == 0) {
            throw new ModelConfigurationNotFoundException(endpointId);
        }
    }

    private static Set<InferenceCapability> parseCapabilities(java.sql.Array array) throws java.sql.SQLException {
        if (array == null) return Set.of();
        Object value = array.getArray();
        if (!(value instanceof String[] names)) return Set.of();
        EnumSet<InferenceCapability> result = EnumSet.noneOf(InferenceCapability.class);
        for (String name : names) result.add(InferenceCapability.valueOf(name));
        return Set.copyOf(result);
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value.truncatedTo(ChronoUnit.MICROS));
    }

    private record GroupRow(UUID groupId, String ownerUserId, String displayName, TaskType taskType,
                             UUID endpointId, Instant createdAt, Instant updatedAt) {
    }

    public static final class ModelConfigurationNotFoundException extends RuntimeException {
        public ModelConfigurationNotFoundException(UUID id) { super("模型配置不存在或当前用户无权访问: " + id); }
    }

    public static final class ModelConfigurationConflictException extends RuntimeException {
        public ModelConfigurationConflictException(String message) { super(message); }
    }
}
