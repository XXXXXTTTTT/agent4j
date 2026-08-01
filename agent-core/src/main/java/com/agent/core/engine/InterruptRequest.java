package com.agent.core.engine;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 节点执行前产生的人工中断请求。
 *
 * @param interruptId 中断标识
 * @param nodeName    待执行节点的精确名称
 * @param reason      中断原因
 * @param details     提供给审批者的结构化详情
 */
public record InterruptRequest(
        UUID interruptId,
        String nodeName,
        String reason,
        Map<String, String> details) {

    /** 校验请求并冻结详情。 */
    public InterruptRequest {
        Objects.requireNonNull(interruptId, "interruptId 不能为空");
        requireText(nodeName, "nodeName");
        requireText(reason, "reason");
        details = Map.copyOf(Objects.requireNonNull(details, "details 不能为空"));
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }
}
