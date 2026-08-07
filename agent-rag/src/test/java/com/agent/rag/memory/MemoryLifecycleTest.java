package com.agent.rag.memory;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryLifecycleTest {

    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void scoresArchitectureRulesWithoutTimeDecay() {
        MemoryEntry entry = entry(
                MemoryType.ARCHITECTURE_RULE,
                0.8,
                0,
                CREATED_AT,
                CREATED_AT);

        assertThat(MemoryLifecycle.score(entry, CREATED_AT.plus(Duration.ofDays(365))))
                .isEqualTo(0.8);
    }

    @Test
    void appliesExactHalfLifeAndAccessFrequency() {
        MemoryEntry preference = entry(
                MemoryType.USER_PREFERENCE,
                0.8,
                0,
                CREATED_AT,
                CREATED_AT);
        MemoryEntry badCase = entry(
                MemoryType.BAD_CASE,
                0.8,
                0,
                CREATED_AT,
                CREATED_AT);
        MemoryEntry accessed = entry(
                MemoryType.USER_PREFERENCE,
                0.8,
                3,
                CREATED_AT,
                CREATED_AT);

        assertThat(MemoryLifecycle.score(
                preference, CREATED_AT.plus(Duration.ofDays(30))))
                .isCloseTo(0.28, org.assertj.core.data.Offset.offset(1.0e-12));
        assertThat(MemoryLifecycle.score(
                badCase, CREATED_AT.plus(Duration.ofDays(14))))
                .isCloseTo(0.28, org.assertj.core.data.Offset.offset(1.0e-12));

        double frequency = Math.log1p(3) / Math.log1p(4);
        double expected = 0.8 * (0.7 + 0.3 * frequency);
        assertThat(MemoryLifecycle.score(accessed, CREATED_AT))
                .isCloseTo(expected, org.assertj.core.data.Offset.offset(1.0e-12));
    }

    @Test
    void preservesCompatibilityConstructorsAndRankingFields() {
        MemoryDraft draft = new MemoryDraft(
                MemoryType.USER_PREFERENCE, "style", "Use records");
        MemoryEntry entry = new MemoryEntry(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "repo", "user", draft.type(), draft.title(), draft.content(),
                "a".repeat(64), embedding(), CREATED_AT, CREATED_AT);
        MemoryHit legacyHit = new MemoryHit(entry, 0.5, 0.25, 0.4);

        assertThat(draft.importance()).isEqualTo(0.5);
        assertThat(entry.importance()).isEqualTo(0.5);
        assertThat(entry.accessCount()).isZero();
        assertThat(entry.lastAccessedAt()).isEqualTo(CREATED_AT);
        assertThat(legacyHit.lifecycleScore()).isEqualTo(1.0);
        assertThat(legacyHit.rankingScore()).isEqualTo(0.52);
    }

    @Test
    void rejectsInvalidLifecycleMetadata() {
        assertThatThrownBy(() -> new MemoryDraft(
                MemoryType.BAD_CASE, "bad", "content", Double.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("importance");
        assertThatThrownBy(() -> entry(
                MemoryType.BAD_CASE, 1.1, 0, CREATED_AT, CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("importance");
        assertThatThrownBy(() -> entry(
                MemoryType.BAD_CASE, 0.5, -1, CREATED_AT, CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accessCount");
        assertThatThrownBy(() -> entry(
                MemoryType.BAD_CASE,
                0.5,
                0,
                CREATED_AT,
                CREATED_AT.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lastAccessedAt");
    }

    private MemoryEntry entry(
            MemoryType type,
            double importance,
            long accessCount,
            Instant createdAt,
            Instant lastAccessedAt) {
        return new MemoryEntry(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "repo",
                "user",
                type,
                "title",
                "content",
                "a".repeat(64),
                embedding(),
                createdAt,
                createdAt,
                importance,
                accessCount,
                lastAccessedAt);
    }

    private float[] embedding() {
        return new float[]{1, 0, 0, 0, 0, 0, 0, 0};
    }
}
