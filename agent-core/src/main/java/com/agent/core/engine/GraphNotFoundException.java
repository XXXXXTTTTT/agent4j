package com.agent.core.engine;

/** 指定图标识未注册。 */
public class GraphNotFoundException extends RuntimeException {

    private final String graphId;

    /** 创建图不存在异常。 */
    public GraphNotFoundException(String graphId) {
        super("图未注册: " + graphId);
        if (graphId == null || graphId.isBlank()) {
            throw new IllegalArgumentException("graphId 不能为空");
        }
        this.graphId = graphId;
    }

    /** 返回未注册的精确图标识。 */
    public String graphId() {
        return graphId;
    }
}
