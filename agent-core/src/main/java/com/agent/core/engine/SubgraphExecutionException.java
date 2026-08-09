package com.agent.core.engine;

import java.util.Objects;

/** 子图执行失败，保留子图标识与原始异常链。 */
public final class SubgraphExecutionException extends RuntimeException {

    private final String subgraphId;

    /** 创建子图执行异常。 */
    public SubgraphExecutionException(String subgraphId, Throwable cause) {
        super("子图执行失败: " + requireText(subgraphId, "subgraphId"),
                Objects.requireNonNull(cause, "cause 不能为空"));
        this.subgraphId = subgraphId;
    }

    /** 返回子图精确标识。 */
    public String subgraphId() {
        return subgraphId;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }
}
