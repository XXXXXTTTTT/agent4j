package com.agent.rag.memory;

import com.agent.rag.embedding.EmbeddingModel;
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
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcMemoryStoreIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    private static final Instant CREATED = Instant.parse("2026-08-03T10:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-08-03T10:05:00Z");
    private static final UUID EXISTING_ID =
            UUID.fromString("6c2fcad3-831b-4f55-a726-1eb5f2e0f7c1");

    private DataSource dataSource;
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
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V1__create_rag_tables.sql"),
                new ClassPathResource("db/migration/V2__create_memory_table.sql"))
                .execute(dataSource);
        new JdbcTemplate(dataSource).update("delete from rag_memories");
        store = new JdbcMemoryStore(dataSource);
    }

    @Test
    void migrationCreatesMemoryTableAndExactIndexes() {
        assertThat(store.tableNames()).containsExactly("rag_memories");
        assertThat(store.indexNames()).contains(
                "idx_rag_memories_scope",
                "idx_rag_memories_search_vector",
                "idx_rag_memories_embedding");
        assertThat(store.vectorDimension()).isEqualTo(8);
    }

    @Test
    void upsertsAndKeepsExistingIdentityOnDuplicateContent() {
        MemoryEntry first = entry(EXISTING_ID, "repo-a", "user-a", MemoryType.USER_PREFERENCE,
                "Constructor", "Use constructors.", "a".repeat(64), CREATED, CREATED);
        MemoryEntry updated = entry(
                UUID.randomUUID(), "repo-a", "user-a", MemoryType.USER_PREFERENCE,
                "Constructor updated", "Use constructor injection.", "a".repeat(64),
                CREATED, UPDATED);

        assertThat(store.upsertAll(List.of(first))).singleElement().satisfies(saved -> {
            assertThat(saved.memoryId()).isEqualTo(EXISTING_ID);
            assertThat(saved.createdAt()).isEqualTo(CREATED);
        });
        assertThat(store.upsertAll(List.of(updated))).singleElement().satisfies(saved -> {
            assertThat(saved.memoryId()).isEqualTo(EXISTING_ID);
            assertThat(saved.title()).isEqualTo("Constructor updated");
            assertThat(saved.updatedAt()).isEqualTo(UPDATED);
        });
    }

    @Test
    void isolatesExactRepositoryUserAndTypeAndQueriesBothIndexes() {
        MemoryEntry scoped = entry(
                EXISTING_ID, "repo-a", "user-a", MemoryType.USER_PREFERENCE,
                "Terminal", "Keep ANSI terminal output.", "b".repeat(64), CREATED, CREATED);
        MemoryEntry otherUser = entry(
                UUID.randomUUID(), "repo-a", "user-b", MemoryType.USER_PREFERENCE,
                "Terminal", "Keep ANSI terminal output.", "c".repeat(64), CREATED, CREATED);
        MemoryEntry otherType = entry(
                UUID.randomUUID(), "repo-a", "user-a", MemoryType.BAD_CASE,
                "Terminal", "Keep ANSI terminal output.", "d".repeat(64), CREATED, CREATED);
        store.upsertAll(List.of(scoped, otherUser, otherType));
        MemoryQuery query = new MemoryQuery(
                "repo-a", "user-a", "ANSI terminal",
                Set.of(MemoryType.USER_PREFERENCE), 10);

        assertThat(store.findByVector(query, new float[]{1, 0, 0, 0, 0, 0, 0, 0}, 10))
                .extracting(row -> row.entry().memoryId())
                .containsExactly(EXISTING_ID);
        assertThat(store.findByLexical(query, 10))
                .extracting(row -> row.entry().memoryId())
                .containsExactly(EXISTING_ID);
    }

    @Test
    void rollsBackWholeBatchWhenSecondMemoryIdViolatesPrimaryKey() {
        MemoryEntry old = entry(
                EXISTING_ID, "repo-a", "user-a", MemoryType.BAD_CASE,
                "Old", "Old memory.", "e".repeat(64), CREATED, CREATED);
        store.upsertAll(List.of(old));
        MemoryEntry first = entry(
                UUID.randomUUID(), "repo-a", "user-a", MemoryType.BAD_CASE,
                "New", "New memory.", "f".repeat(64), CREATED, UPDATED);
        MemoryEntry duplicateId = entry(
                EXISTING_ID, "repo-a", "user-a", MemoryType.ARCHITECTURE_RULE,
                "Conflicting", "Conflicting memory.", "0".repeat(64), CREATED, UPDATED);

        assertThatThrownBy(() -> store.upsertAll(List.of(first, duplicateId)))
                .isInstanceOf(MemoryStoreException.class)
                .hasCauseInstanceOf(RuntimeException.class);
        assertThat(store.findByLexical(
                new MemoryQuery("repo-a", "user-a", "Old", Set.of(MemoryType.BAD_CASE), 10),
                10)).extracting(row -> row.entry().memoryId()).containsExactly(EXISTING_ID);
        assertThat(store.findByLexical(
                new MemoryQuery("repo-a", "user-a", "New", Set.of(MemoryType.BAD_CASE), 10),
                10)).isEmpty();
    }

    @Test
    void capturesDeduplicatesAndRecallsThroughRealStore() {
        MemoryDraft draft = new MemoryDraft(
                MemoryType.ARCHITECTURE_RULE,
                "Patch policy",
                "Use narrow Unified Diff patches.");
        MemoryManager manager = new MemoryManager(
                capture -> List.of(draft),
                store,
                new EmbeddingModel() {
                    @Override
                    public int dimensions() {
                        return 8;
                    }

                    @Override
                    public float[] embed(String text) {
                        return text.contains("Unified Diff")
                                ? new float[]{1, 0, 0, 0, 0, 0, 0, 0}
                                : new float[]{0, 1, 0, 0, 0, 0, 0, 0};
                    }
                },
                java.time.Clock.fixed(CREATED, java.time.ZoneOffset.UTC),
                UUID::randomUUID);

        manager.capture(new MemoryCapture("repo-a", "user-a", "confirmed rule"));
        manager.capture(new MemoryCapture("repo-a", "user-a", "confirmed rule"));

        List<MemoryHit> hits = manager.recall(new MemoryQuery(
                "repo-a", "user-a", "Unified Diff",
                Set.of(MemoryType.ARCHITECTURE_RULE), 10));
        assertThat(hits).singleElement()
                .extracting(hit -> hit.entry().content())
                .isEqualTo("Use narrow Unified Diff patches.");
        assertThat(store.findByLexical(new MemoryQuery(
                "repo-a", "user-a", "Unified Diff",
                Set.of(MemoryType.ARCHITECTURE_RULE), 10), 10)).hasSize(1);
        assertThat(store.findByLexical(new MemoryQuery(
                "repo-b", "user-a", "Unified Diff",
                Set.of(MemoryType.ARCHITECTURE_RULE), 10), 10)).isEmpty();
    }

    private MemoryEntry entry(
            UUID id,
            String repositoryId,
            String userId,
            MemoryType type,
            String title,
            String content,
            String hash,
            Instant createdAt,
            Instant updatedAt) {
        return new MemoryEntry(
                id, repositoryId, userId, type, title, content, hash,
                new float[]{1, 0, 0, 0, 0, 0, 0, 0}, createdAt, updatedAt);
    }
}
