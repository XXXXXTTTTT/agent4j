package com.agent.rag.memory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcMemoryLifecycleIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    private static final Instant CREATED_AT = Instant.parse("2026-08-07T01:00:00Z");
    private static final Instant ACCESSED_AT = Instant.parse("2026-08-07T02:00:00Z");
    private static final UUID MEMORY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000021");

    private JdbcTemplate jdbcTemplate;
    private JdbcMemoryStore store;

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
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @BeforeEach
    void setUpDatabase() {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        new JdbcTemplate(dataSource).execute("drop table if exists rag_memories");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/rag-migration/V1__create_rag_tables.sql"),
                new ClassPathResource("db/rag-migration/V2__create_memory_table.sql"),
                new ClassPathResource("db/rag-migration/V3__add_memory_lifecycle.sql"))
                .execute(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.update("delete from rag_memories");
        store = new JdbcMemoryStore(dataSource);
    }

    @Test
    void migrationAddsLifecycleColumnsDefaultsAndIndex() {
        assertThat(jdbcTemplate.queryForList("""
                select column_name from information_schema.columns
                where table_schema = 'public' and table_name = 'rag_memories'
                  and column_name in ('importance', 'access_count', 'last_accessed_at')
                order by column_name
                """, String.class)).containsExactly(
                        "access_count", "importance", "last_accessed_at");
        assertThat(jdbcTemplate.queryForList("""
                select indexname from pg_indexes
                where schemaname = 'public' and tablename = 'rag_memories'
                  and indexname = 'idx_rag_memories_lifecycle'
                """, String.class)).containsExactly("idx_rag_memories_lifecycle");

        jdbcTemplate.update("""
                insert into rag_memories(
                    memory_id, repository_id, user_id, memory_type, title, content,
                    content_hash, embedding, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, cast(? as vector), ?, ?)
                """,
                MEMORY_ID, "repo", "user", MemoryType.USER_PREFERENCE.name(),
                "title", "content", "a".repeat(64), "[1,0,0,0,0,0,0,0]",
                Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT));

        var defaults = jdbcTemplate.queryForMap("""
                select importance, access_count, last_accessed_at
                from rag_memories where memory_id = ?
                """, MEMORY_ID);
        assertThat(((Number) defaults.get("importance")).doubleValue()).isEqualTo(0.5);
        assertThat(((Number) defaults.get("access_count")).longValue()).isZero();
        assertThat(defaults.get("last_accessed_at")).isNotNull();
    }

    @Test
    void upsertPersistsLifecycleAndRecordAccessKeepsExactScope() {
        MemoryEntry scoped = entry(MEMORY_ID, "repo-a", "user-a", 0.9);
        UUID otherId = UUID.fromString("00000000-0000-0000-0000-000000000022");
        MemoryEntry otherUser = entry(otherId, "repo-a", "user-b", 0.6);
        store.upsertAll(List.of(scoped, otherUser));

        MemoryQuery scope = new MemoryQuery(
                "repo-a", "user-a", "content",
                Set.of(MemoryType.USER_PREFERENCE), 10);
        store.recordAccess(scope, List.of(MEMORY_ID, otherId), ACCESSED_AT);

        MemoryEntry updated = store.findByVector(
                        scope, new float[]{1, 0, 0, 0, 0, 0, 0, 0}, 10)
                .getFirst().entry();
        assertThat(updated.importance()).isEqualTo(0.9);
        assertThat(updated.accessCount()).isEqualTo(1);
        assertThat(updated.lastAccessedAt()).isEqualTo(ACCESSED_AT);

        var untouched = jdbcTemplate.queryForMap("""
                select access_count, last_accessed_at from rag_memories where memory_id = ?
                """, otherId);
        assertThat(((Number) untouched.get("access_count")).longValue()).isZero();
        assertThat(((Timestamp) untouched.get("last_accessed_at")).toInstant())
                .isEqualTo(CREATED_AT);
    }

    private MemoryEntry entry(UUID id, String repositoryId, String userId, double importance) {
        return new MemoryEntry(
                id,
                repositoryId,
                userId,
                MemoryType.USER_PREFERENCE,
                "title-" + id,
                "content",
                id.equals(MEMORY_ID) ? "b".repeat(64) : "c".repeat(64),
                new float[]{1, 0, 0, 0, 0, 0, 0, 0},
                CREATED_AT,
                CREATED_AT,
                importance,
                0,
                CREATED_AT);
    }
}
