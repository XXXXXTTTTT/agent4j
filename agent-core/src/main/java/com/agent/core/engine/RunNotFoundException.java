package com.agent.core.engine;

import java.util.Objects;
import java.util.UUID;

/** 指定 Run 不存在。 */
public class RunNotFoundException extends RuntimeException {

    private final UUID runId;

    /** 创建 Run 不存在异常。 */
    public RunNotFoundException(UUID runId) {
        super("Run 不存在: " + Objects.requireNonNull(runId, "runId 不能为空"));
        this.runId = runId;
    }

    /** 返回 Run 标识。 */
    public UUID runId() {
        return runId;
    }
}
