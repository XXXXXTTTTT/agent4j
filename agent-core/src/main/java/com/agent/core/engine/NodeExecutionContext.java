package com.agent.core.engine;

import java.util.Objects;
import java.util.UUID;

/**
 * 节点执行所属的 Run 与节点上下文。
 *
 * @param runId Run 标识
 * @param nodeName 当前节点精确名称
 */
public record NodeExecutionContext(UUID runId, String nodeName) {

    /** 校验节点执行上下文。 */
    public NodeExecutionContext {
        Objects.requireNonNull(runId, "runId 不能为空");
        if (nodeName == null || nodeName.isBlank()) {
            throw new IllegalArgumentException("nodeName 不能为空");
        }
    }
}
