package com.agent.rag.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** 计算长期记忆的重要度、访问频率和时间衰减分数。 */
public final class MemoryLifecycle {

    /** 用户偏好的半衰期。 */
    public static final Duration PREFERENCE_HALF_LIFE = Duration.ofDays(30);

    /** 历史 Bad Case 的半衰期。 */
    public static final Duration BAD_CASE_HALF_LIFE = Duration.ofDays(14);

    private MemoryLifecycle() {
    }

    /** 在指定时刻计算生命周期分数。 */
    public static double score(MemoryEntry entry, Instant evaluatedAt) {
        Objects.requireNonNull(entry, "entry 不能为空");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt 不能为空");
        if (evaluatedAt.isBefore(entry.lastAccessedAt())) {
            throw new IllegalArgumentException("evaluatedAt 不能早于 lastAccessedAt");
        }
        if (entry.type() == MemoryType.ARCHITECTURE_RULE) {
            return entry.importance();
        }

        Duration halfLife = switch (entry.type()) {
            case USER_PREFERENCE -> PREFERENCE_HALF_LIFE;
            case BAD_CASE -> BAD_CASE_HALF_LIFE;
            case ARCHITECTURE_RULE -> throw new IllegalStateException("架构规则不应进入衰减分支");
        };
        double ageSeconds = Duration.between(entry.lastAccessedAt(), evaluatedAt).toNanos()
                / 1_000_000_000.0;
        double decay = Math.exp(
                -Math.log(2.0) * ageSeconds / halfLife.toSeconds());
        double frequency = Math.log1p(entry.accessCount())
                / Math.log1p(entry.accessCount() + 1.0);
        return entry.importance() * (0.7 + 0.3 * frequency) * decay;
    }
}
