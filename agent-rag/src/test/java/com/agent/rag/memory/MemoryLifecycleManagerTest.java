package com.agent.rag.memory;

import com.agent.rag.embedding.EmbeddingModel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MemoryLifecycleManagerTest {

    private static final Instant NOW = Instant.parse("2026-08-07T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void combinesRetrievalAndLifecycleScoresAndRecordsAccess() {
        MemoryEntry rule = entry(
                "00000000-0000-0000-0000-000000000031",
                MemoryType.ARCHITECTURE_RULE,
                0.9,
                0,
                NOW.minusSeconds(10));
        MemoryEntry preference = entry(
                "00000000-0000-0000-0000-000000000032",
                MemoryType.USER_PREFERENCE,
                0.9,
                0,
                NOW);
        RecordingStore store = new RecordingStore();
        store.vectorRows = List.of(
                new MemoryRetrievalRow(rule, 1),
                new MemoryRetrievalRow(preference, 0));
        store.lexicalRows = List.of(
                new MemoryRetrievalRow(rule, 1),
                new MemoryRetrievalRow(preference, 0));
        List<String> audit = new ArrayList<>();
        MemoryManager manager = new MemoryManager(
                capture -> List.of(),
                store,
                embeddingModel(),
                CLOCK,
                UUID::randomUUID,
                (query, ids, failure) -> audit.add(query.repositoryId() + ":" + ids.size()));

        List<MemoryHit> hits = manager.recall(new MemoryQuery(
                "repo", "user", "query",
                Set.of(MemoryType.USER_PREFERENCE, MemoryType.ARCHITECTURE_RULE), 2));

        assertThat(hits).extracting(hit -> hit.entry().type())
                .containsExactly(MemoryType.ARCHITECTURE_RULE, MemoryType.USER_PREFERENCE);
        assertThat(hits.getFirst().lifecycleScore()).isEqualTo(0.9);
        assertThat(hits.getFirst().rankingScore()).isCloseTo(0.98, within(1.0e-12));
        assertThat(store.accessIds).containsExactly(
                UUID.fromString("00000000-0000-0000-0000-000000000031"),
                UUID.fromString("00000000-0000-0000-0000-000000000032"));
        assertThat(audit).isEmpty();
    }

    @Test
    void isolatesAccessUpdateFailureFromCompletedRecall() {
        MemoryEntry entry = entry(
                "00000000-0000-0000-0000-000000000033",
                MemoryType.USER_PREFERENCE,
                0.5,
                0,
                NOW);
        RecordingStore store = new RecordingStore();
        store.vectorRows = List.of(new MemoryRetrievalRow(entry, 1));
        store.failAccess = true;
        List<String> audit = new ArrayList<>();
        MemoryManager manager = new MemoryManager(
                capture -> List.of(),
                store,
                embeddingModel(),
                CLOCK,
                UUID::randomUUID,
                (query, ids, failure) -> {
                    assertThat(failure).isInstanceOf(MemoryStoreException.class);
                    audit.add("access-failure");
                });

        assertThat(manager.recall(new MemoryQuery(
                "repo", "user", "query", Set.of(MemoryType.USER_PREFERENCE), 1)))
                .singleElement()
                .extracting(MemoryHit::entry)
                .isEqualTo(entry);
        assertThat(audit).containsExactly("access-failure");
    }

    private MemoryEntry entry(
            String id,
            MemoryType type,
            double importance,
            long accessCount,
            Instant lastAccessedAt) {
        return new MemoryEntry(
                UUID.fromString(id),
                "repo",
                "user",
                type,
                id,
                "content",
                "a".repeat(64),
                new float[8],
                NOW.minusSeconds(10),
                NOW.minusSeconds(5),
                importance,
                accessCount,
                lastAccessedAt);
    }

    private EmbeddingModel embeddingModel() {
        return new EmbeddingModel() {
            @Override
            public int dimensions() {
                return 8;
            }

            @Override
            public float[] embed(String text) {
                return new float[8];
            }
        };
    }

    private static final class RecordingStore implements MemoryStore {
        private List<MemoryRetrievalRow> vectorRows = List.of();
        private List<MemoryRetrievalRow> lexicalRows = List.of();
        private final List<UUID> accessIds = new ArrayList<>();
        private boolean failAccess;

        @Override
        public List<MemoryEntry> upsertAll(List<MemoryEntry> entries) {
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

        @Override
        public void recordAccess(MemoryQuery query, List<UUID> memoryIds, Instant accessedAt) {
            if (failAccess) {
                throw new MemoryStoreException("access update failed", new IllegalStateException("db down"));
            }
            accessIds.addAll(memoryIds);
        }
    }
}
