package com.agent.core.engine;

import java.util.Objects;
import java.util.UUID;

/** Checkpoint 追加时版本或 Run 状态发生冲突。 */
public class CheckpointConflictException extends RuntimeException {

    private final UUID runId;
    private final long expectedVersion;

    /** 创建 Checkpoint 冲突异常。 */
    public CheckpointConflictException(UUID runId, long expectedVersion) {
        super("Checkpoint 冲突: runId="
                + Objects.requireNonNull(runId, "runId 不能为空")
                + ", expectedVersion=" + expectedVersion);
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion 不能小于 0");
        }
        this.runId = runId;
        this.expectedVersion = expectedVersion;
    }

    /** 返回 Run 标识。 */
    public UUID runId() {
        return runId;
    }

    /** 返回预期版本。 */
    public long expectedVersion() {
        return expectedVersion;
    }
}
