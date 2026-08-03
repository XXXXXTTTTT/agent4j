package com.agent.rag.memory;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryDomainTest {

    @Test
    void validatesCaptureAndDraftLimits() {
        assertThatThrownBy(() -> new MemoryCapture(" ", "user", "source"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryCapture("repo", " ", "source"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryCapture("repo", "user", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryCapture("repo", "user", "x".repeat(20_001)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryDraft(null, "title", "content"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MemoryDraft(MemoryType.BAD_CASE, " ", "content"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryDraft(MemoryType.BAD_CASE, "title", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryDraft(MemoryType.BAD_CASE, "t".repeat(201), "content"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryDraft(MemoryType.BAD_CASE, "title", "x".repeat(4_001)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void freezesQueryTypesAndEmbedding() {
        Set<MemoryType> types = EnumSet.of(MemoryType.USER_PREFERENCE, MemoryType.BAD_CASE);
        MemoryQuery query = new MemoryQuery("repo", "user", "task", types, 20);
        types.clear();
        assertThat(query.types()).containsExactlyInAnyOrder(
                MemoryType.USER_PREFERENCE, MemoryType.BAD_CASE);
        assertThatThrownBy(() -> query.types().clear())
                .isInstanceOf(UnsupportedOperationException.class);

        float[] original = {1, 2, 3, 4, 5, 6, 7, 8};
        MemoryEntry entry = new MemoryEntry(
                UUID.randomUUID(),
                "repo",
                "user",
                MemoryType.USER_PREFERENCE,
                "title",
                "content",
                "a".repeat(64),
                original,
                Instant.parse("2026-08-03T10:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z"));
        original[0] = 99;
        float[] returned = entry.embedding();
        returned[1] = 99;

        assertThat(entry.embedding()).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
    }

    @Test
    void validatesEntryAndHitScores() {
        MemoryEntry entry = new MemoryEntry(
                UUID.randomUUID(), "repo", "user", MemoryType.ARCHITECTURE_RULE,
                "title", "content", "b".repeat(64),
                new float[8], Instant.now(), Instant.now());

        assertThatThrownBy(() -> new MemoryEntry(
                entry.memoryId(), entry.repositoryId(), entry.userId(), entry.type(),
                entry.title(), entry.content(), "bad", entry.embedding(),
                entry.createdAt(), entry.updatedAt()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryHit(entry, -1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryHit(entry, Double.NaN, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
