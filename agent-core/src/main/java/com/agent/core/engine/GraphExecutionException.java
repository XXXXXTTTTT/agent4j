package com.agent.core.engine;

import java.util.Objects;

/**
 * 节点调度或执行失败。
 */
public class GraphExecutionException extends RuntimeException {

    private final String nodeName;

    /**
     * 创建图执行异常。
     *
     * @param nodeName 失败节点名称
     * @param cause    原始异常
     */
    public GraphExecutionException(String nodeName, Throwable cause) {
        super("节点执行失败: " + Objects.requireNonNull(nodeName, "nodeName 不能为空"), cause);
        this.nodeName = nodeName;
    }

    /**
     * 返回失败节点名称。
     *
     * @return 节点名称
     */
    public String nodeName() {
        return nodeName;
    }
}
