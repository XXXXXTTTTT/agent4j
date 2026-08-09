package com.agent.core.engine;

import java.util.Objects;

/** 子图在人工中断点挂起，保留精确审批请求。 */
public final class SubgraphInterruptedException extends RuntimeException {

    private final String subgraphId;
    private final String nodeName;
    private final InterruptRequest request;

    /** 创建子图中断异常。 */
    public SubgraphInterruptedException(
            String subgraphId,
            String nodeName,
            InterruptRequest request) {
        super("子图执行被中断: " + requireText(subgraphId, "subgraphId")
                + ":" + requireText(nodeName, "nodeName"));
        this.subgraphId = subgraphId;
        this.nodeName = nodeName;
        this.request = Objects.requireNonNull(request, "request 不能为空");
        if (!nodeName.equals(request.nodeName())) {
            throw new IllegalArgumentException("中断请求节点名称不一致");
        }
    }

    /** 返回子图精确标识。 */
    public String subgraphId() {
        return subgraphId;
    }

    /** 返回发生中断的子图节点。 */
    public String nodeName() {
        return nodeName;
    }

    /** 返回原始中断请求。 */
    public InterruptRequest request() {
        return request;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }
}
