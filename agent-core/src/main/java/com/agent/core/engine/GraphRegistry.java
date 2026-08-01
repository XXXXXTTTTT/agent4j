package com.agent.core.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 按精确图标识创建独立状态图。 */
public final class GraphRegistry {

    private final Map<String, GraphFactory> factories;

    /**
     * 创建图注册表。
     *
     * @param factories 图标识到工厂的精确映射
     */
    public GraphRegistry(Map<String, GraphFactory> factories) {
        Objects.requireNonNull(factories, "factories 不能为空");
        if (factories.isEmpty()) {
            throw new IllegalArgumentException("factories 不能为空映射");
        }
        Map<String, GraphFactory> checked = new LinkedHashMap<>();
        factories.forEach((graphId, factory) -> {
            requireGraphId(graphId);
            checked.put(graphId, Objects.requireNonNull(factory, "factory 不能为空"));
        });
        this.factories = Map.copyOf(checked);
    }

    /**
     * 创建指定图的独立实例。
     *
     * @param graphId 精确图标识
     * @return 新状态图
     */
    public StateGraph create(String graphId) {
        requireGraphId(graphId);
        GraphFactory factory = factories.get(graphId);
        if (factory == null) {
            throw new GraphNotFoundException(graphId);
        }
        return Objects.requireNonNull(factory.create(), "GraphFactory 返回值不能为空");
    }

    private static void requireGraphId(String graphId) {
        if (graphId == null || graphId.isBlank()) {
            throw new IllegalArgumentException("graphId 不能为空");
        }
    }
}
