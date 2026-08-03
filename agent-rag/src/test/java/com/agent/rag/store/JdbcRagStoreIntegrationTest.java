package com.agent.rag.store;

import com.agent.rag.domain.ChildChunk;
import com.agent.rag.domain.ParentChunk;
import com.agent.rag.domain.RagHit;
import com.agent.rag.domain.RagQuery;
import com.agent.rag.embedding.EmbeddingModel;
import com.agent.rag.ingest.CodebaseIngestionService;
import com.agent.rag.search.HybridRagRetriever;
import com.agent.sandbox.ast.AstService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcRagStoreIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-03T10:00:00Z"), ZoneOffset.UTC);
    private static final UUID PARENT_ID =
            UUID.fromString("c4b37c2e-5964-42e5-a8ad-0b5c759a21dc");
    private static final UUID CHILD_ID =
            UUID.fromString("7698c6fb-2939-4ae1-84c9-44a3fe7a7ec0");

    private DataSource dataSource;
    private JdbcRagStore store;

    @TempDir
    Path temporaryDirectory;

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
                new ClassPathResource("db/migration/V1__create_rag_tables.sql"))
                .execute(dataSource);
        store = new JdbcRagStore(dataSource, CLOCK);
        store.replaceRepository("repo-a", List.of(), List.of());
        store.replaceRepository("repo-b", List.of(), List.of());
    }

    @Test
    void migrationCreatesVectorExtensionTablesAndIndexes() {
        assertThat(store.tableNames())
                .containsExactly("rag_child_chunks", "rag_parent_chunks");
        assertThat(store.indexNames())
                .contains("idx_rag_child_search_vector", "idx_rag_child_embedding");
        assertThat(store.vectorDimension()).isEqualTo(8);
    }

    @Test
    void replacesRowsAndQueriesVectorAndLexicalData() {
        ParentChunk parent = parent("repo-a", "src/App.java", "com.example.App");
        ChildChunk child = child("repo-a", parent.parentId(),
                "com.example.App#public void run()", "public void run() { return; }",
                new float[]{1, 0, 0, 0, 0, 0, 0, 0});
        store.replaceRepository("repo-a", List.of(parent), List.of(child));

        assertThat(store.findByVector("repo-a", child.embedding(), 10))
                .extracting(RetrievalRow::childChunk)
                .containsExactly(child);
        assertThat(store.findByVector(
                "repo-a", new float[]{-1, 0, 0, 0, 0, 0, 0, 0}, 10))
                .singleElement()
                .extracting(RetrievalRow::retrievalScore)
                .isEqualTo(0.0);
        assertThat(store.findByLexical("repo-a", "return", 10))
                .extracting(RetrievalRow::childChunk)
                .containsExactly(child);
        assertThat(store.countChildren("repo-a")).isEqualTo(1);
        assertThat(store.averageDocumentLength("repo-a")).isPositive();
        assertThat(store.documentFrequencies("repo-a", List.of("return")))
                .containsEntry("return", 1L);
    }

    @Test
    void replacementRollsBackWhenChildForeignKeyFails() {
        ParentChunk oldParent = parent("repo-a", "src/Old.java", "com.example.Old");
        ChildChunk oldChild = child("repo-a", oldParent.parentId(),
                "com.example.Old#void old()", "void old() {}", new float[8]);
        store.replaceRepository("repo-a", List.of(oldParent), List.of(oldChild));

        ParentChunk newParent = parent("repo-a", "src/New.java", "com.example.New");
        ChildChunk invalidChild = child("repo-a",
                UUID.fromString("4dff921d-bb83-4c5c-9f19-6bd5c3c190ce"),
                "com.example.New#void newMethod()", "void newMethod() {}", new float[8]);

        assertThatThrownBy(() -> store.replaceRepository(
                "repo-a", List.of(newParent), List.of(invalidChild)))
                .isInstanceOf(RagStoreException.class)
                .hasCauseInstanceOf(RuntimeException.class);
        assertThat(store.findByLexical("repo-a", "old", 10))
                .extracting(RetrievalRow::childChunk)
                .containsExactly(oldChild);
        assertThat(store.findByLexical("repo-a", "newMethod", 10)).isEmpty();
    }

    @Test
    void repositoryRowsRemainIsolated() {
        ParentChunk parent = parent("repo-b", "src/B.java", "com.example.B");
        ChildChunk child = child("repo-b", parent.parentId(),
                "com.example.B#void onlyB()", "void onlyB() {}", new float[8]);
        store.replaceRepository("repo-b", List.of(parent), List.of(child));

        assertThat(store.findByLexical("repo-a", "onlyB", 10)).isEmpty();
        assertThat(store.findByLexical("repo-b", "onlyB", 10))
                .extracting(RetrievalRow::childChunk)
                .containsExactly(child);
    }

    @Test
    void ingestsJavaFixtureAndReplacesRepositoryForHybridRetrieval() throws Exception {
        Path source = temporaryDirectory.resolve("src/main/java/fixture/Fixture.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package fixture;

                public class Fixture {
                    public void needle() {
                        System.out.println("needle");
                    }

                    public void needle(int count) {
                        System.out.println(count);
                    }
                }
                """, StandardCharsets.UTF_8);
        EmbeddingModel model = new EmbeddingModel() {
            @Override
            public int dimensions() {
                return 8;
            }

            @Override
            public float[] embed(String text) {
                return text.contains("needle")
                        ? new float[]{1, 0, 0, 0, 0, 0, 0, 0}
                        : new float[]{0, 1, 0, 0, 0, 0, 0, 0};
            }
        };
        CodebaseIngestionService ingestion =
                new CodebaseIngestionService(new AstService(), model, store);
        ingestion.ingest(temporaryDirectory, "repo-ingest");

        assertThat(store.countParents("repo-ingest")).isEqualTo(1);
        assertThat(store.countChildren("repo-ingest")).isEqualTo(2);
        assertThat(store.findByLexical("repo-ingest", "needle", 10))
                .extracting(row -> row.childChunk().symbol())
                .containsExactly(
                        "fixture.Fixture#public void needle()",
                        "fixture.Fixture#public void needle(int count)");
        List<RagHit> hits = new HybridRagRetriever(store, model).search(
                new RagQuery("repo-ingest", "needle", null, 10));
        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().childChunk().content()).contains("needle");

        Files.writeString(source, """
                package fixture;

                public class Fixture {
                    public void replacement() {
                        System.out.println("replacement");
                    }
                }
                """, StandardCharsets.UTF_8);
        ingestion.ingest(temporaryDirectory, "repo-ingest");

        assertThat(store.countChildren("repo-ingest")).isEqualTo(1);
        assertThat(store.findByLexical("repo-ingest", "needle", 10)).isEmpty();
        assertThat(store.findByLexical("repo-ingest", "replacement", 10))
                .singleElement()
                .extracting(row -> row.childChunk().symbol())
                .isEqualTo("fixture.Fixture#public void replacement()");
    }

    private ParentChunk parent(String repositoryId, String path, String symbol) {
        return new ParentChunk(
                PARENT_ID,
                repositoryId,
                path,
                symbol,
                "class App { void run() { return; } }",
                1,
                1,
                "{\"kind\":\"JAVA_CLASS\"}");
    }

    private ChildChunk child(
            String repositoryId,
            UUID parentId,
            String symbol,
            String content,
            float[] embedding) {
        return new ChildChunk(
                CHILD_ID,
                parentId,
                repositoryId,
                "src/App.java",
                symbol,
                0,
                content,
                1,
                1,
                embedding);
    }
}
