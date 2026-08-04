package com.agent.rag.memory;

import com.agent.rag.embedding.EmbeddingModel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryManagerTest {

    private static final Instant NOW = Instant.parse("2026-08-03T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void capturesExactHashEmbeddingTimeAndOrder() {
        MemoryDraft first = new MemoryDraft(
                MemoryType.USER_PREFERENCE, "Style", "Use constructors.");
        MemoryDraft second = new MemoryDraft(
                MemoryType.BAD_CASE, "Timeout", "Always clean PTY.");
        RecordingStore store = new RecordingStore();
        AtomicInteger sequence = new AtomicInteger();
        MemoryManager manager = new MemoryManager(
                capture -> List.of(first, second),
                store,
                embeddingModel(),
                CLOCK,
                () -> UUID.nameUUIDFromBytes(("id-" + sequence.getAndIncrement())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        List<MemoryEntry> saved = manager.capture(
                new MemoryCapture("repo", "user", "raw observation"));

        assertThat(saved).hasSize(2);
        assertThat(store.saved).extracting(MemoryEntry::title)
                .containsExactly("Style", "Timeout");
        assertThat(store.saved.getFirst().contentHash())
                .isEqualTo(sha256("USER_PREFERENCE\nStyle\nUse constructors."));
        assertThat(store.saved.getFirst().embedding()).containsExactly(
                0, 1, 2, 3, 4, 5, 6, 7);
        assertThat(store.saved).allSatisfy(entry -> {
            assertThat(entry.createdAt()).isEqualTo(NOW);
            assertThat(entry.updatedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void emptyExtractionDoesNotWrite() {
        RecordingStore store = new RecordingStore();
        MemoryManager manager = new MemoryManager(
                capture -> List.of(), store, embeddingModel(), CLOCK, UUID::randomUUID);

        assertThat(manager.capture(new MemoryCapture("repo", "user", "source"))).isEmpty();
        assertThat(store.upsertCalls).isZero();
    }

    @Test
    void rejectsMixedTypesBeforeEmbeddingOrStoreForBadCases() {
        RecordingStore store = new RecordingStore();
        AtomicInteger embeddings = new AtomicInteger();
        MemoryManager manager = new MemoryManager(
                capture -> List.of(
                        new MemoryDraft(MemoryType.BAD_CASE, "bad", "detail"),
                        new MemoryDraft(MemoryType.USER_PREFERENCE, "wrong", "detail")),
                store,
                new EmbeddingModel() {
                    @Override public int dimensions() { return 8; }
                    @Override public float[] embed(String text) {
                        embeddings.incrementAndGet();
                        return new float[8];
                    }
                },
                CLOCK,
                UUID::randomUUID);

        assertThatThrownBy(() -> manager.captureBadCases(
                new MemoryCapture("repo", "user", "source")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BAD_CASE");
        assertThat(embeddings).hasValue(0);
        assertThat(store.upsertCalls).isZero();
    }

    @Test
    void capturesOnlyBadCasesThroughDedicatedEntryPoint() {
        RecordingStore store = new RecordingStore();
        MemoryManager manager = new MemoryManager(
                capture -> List.of(new MemoryDraft(MemoryType.BAD_CASE, "bad", "detail")),
                store, embeddingModel(), CLOCK, UUID::randomUUID);

        List<MemoryEntry> saved = manager.captureBadCases(
                new MemoryCapture("repo", "user", "source"));

        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().type()).isEqualTo(MemoryType.BAD_CASE);
        assertThat(store.upsertCalls).isEqualTo(1);
    }

    @Test
    void mergesAndNormalizesVectorAndLexicalRowsWithStableOrdering() {
        MemoryEntry alpha = entry("00000000-0000-0000-0000-000000000001", "alpha", NOW);
        MemoryEntry beta = entry("00000000-0000-0000-0000-000000000002", "beta", NOW.plusSeconds(1));
        MemoryEntry gamma = entry("00000000-0000-0000-0000-000000000003", "gamma", NOW);
        RecordingStore store = new RecordingStore();
        store.vectorRows = List.of(
                new MemoryRetrievalRow(alpha, 1),
                new MemoryRetrievalRow(beta, 0));
        store.lexicalRows = List.of(
                new MemoryRetrievalRow(beta, 1),
                new MemoryRetrievalRow(gamma, 0));
        MemoryManager manager = new MemoryManager(
                capture -> List.of(), store, embeddingModel(), CLOCK, UUID::randomUUID);

        List<MemoryHit> hits = manager.recall(new MemoryQuery(
                "repo", "user", "query",
                EnumSet.of(MemoryType.USER_PREFERENCE), 3));

        assertThat(hits).extracting(hit -> hit.entry().title())
                .containsExactly("alpha", "beta", "gamma");
        assertThat(hits.getFirst().finalScore()).isEqualTo(0.65);
        assertThat(hits.get(1).finalScore()).isEqualTo(0.35);
    }

    @Test
    void rejectsForeignScopeRows() {
        MemoryEntry foreign = entry(
                "00000000-0000-0000-0000-000000000009", "foreign", "repo-foreign", "user", NOW);
        RecordingStore store = new RecordingStore();
        store.vectorRows = List.of(new MemoryRetrievalRow(foreign, 1));
        MemoryManager manager = new MemoryManager(
                capture -> List.of(), store, embeddingModel(), CLOCK, UUID::randomUUID);

        assertThatThrownBy(() -> manager.recall(new MemoryQuery(
                "repo", "user", "query", Set.of(MemoryType.USER_PREFERENCE), 3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scope");
    }

    @Test
    void formatsAdapterContextWithoutReordering() {
        MemoryEntry first = entry(
                "00000000-0000-0000-0000-000000000011", "first", NOW);
        MemoryEntry second = entry(
                "00000000-0000-0000-0000-000000000012", "second", NOW);
        RecordingStore store = new RecordingStore();
        store.vectorRows = List.of(
                new MemoryRetrievalRow(first, 2), new MemoryRetrievalRow(second, 1));
        MemoryContextProviderAdapter adapter = new MemoryContextProviderAdapter(
                new MemoryManager(capture -> List.of(), store, embeddingModel(), CLOCK,
                        UUID::randomUUID));

        var context = adapter.recall(new com.agent.core.memory.MemoryContextRequest(
                "repo", "user", "query", 2));

        assertThat(context.entryCount()).isEqualTo(2);
        assertThat(context.prompt()).isEqualTo(
                "[USER_PREFERENCE] first\nfirst content\n\n"
                        + "[USER_PREFERENCE] second\nsecond content");
    }

    private EmbeddingModel embeddingModel() {
        return new EmbeddingModel() {
            @Override
            public int dimensions() {
                return 8;
            }

            @Override
            public float[] embed(String text) {
                return new float[]{0, 1, 2, 3, 4, 5, 6, 7};
            }
        };
    }

    private MemoryEntry entry(String id, String title, Instant updatedAt) {
        return entry(id, title, "repo", "user", updatedAt);
    }

    private MemoryEntry entry(
            String id, String title, String repositoryId, String userId, Instant updatedAt) {
        return new MemoryEntry(
                UUID.fromString(id), repositoryId, userId, MemoryType.USER_PREFERENCE,
                title, title + " content", "a".repeat(64),
                new float[8], NOW, updatedAt);
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class RecordingStore implements MemoryStore {
        private final List<MemoryEntry> saved = new ArrayList<>();
        private int upsertCalls;
        private List<MemoryRetrievalRow> vectorRows = List.of();
        private List<MemoryRetrievalRow> lexicalRows = List.of();

        @Override
        public List<MemoryEntry> upsertAll(List<MemoryEntry> entries) {
            upsertCalls++;
            saved.addAll(entries);
            return List.copyOf(entries);
        }

        @Override
        public List<MemoryRetrievalRow> findByVector(
                MemoryQuery query, float[] queryEmbedding, int limit) {
            return vectorRows;
        }

        @Override
        public List<MemoryRetrievalRow> findByLexical(MemoryQuery query, int limit) {
            return lexicalRows;
        }
    }
}
