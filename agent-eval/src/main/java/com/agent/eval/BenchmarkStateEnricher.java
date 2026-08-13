package com.agent.eval;

import com.agent.core.engine.AgentState;

import java.util.Map;
import java.util.Objects;

/** 在启动 Benchmark Run 前补充初始 AgentState 的适配端口。 */
@FunctionalInterface
public interface BenchmarkStateEnricher {

    /** Benchmark 元数据在 AgentState 中使用的变量前缀。 */
    String METADATA_VARIABLE_PREFIX = "benchmark.metadata.";

    /**
     * 根据 Benchmark 任务补充初始状态。
     *
     * @param initialState 已写入基础 Benchmark 字段的初始状态
     * @param task 当前 Benchmark 任务
     * @return 增强后的不可变状态
     */
    AgentState enrich(AgentState initialState, BenchmarkTask task);

    /**
     * 创建将任务元数据逐项写入初始状态的默认增强器。
     *
     * @return 写入 {@code benchmark.metadata.<key>} 的增强器
     */
    static BenchmarkStateEnricher metadata() {
        return (initialState, task) -> {
            AgentState state = Objects.requireNonNull(initialState, "initialState 不能为空");
            Map<String, String> metadata = Objects.requireNonNull(task, "task 不能为空").metadata();
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                String key = requireText(entry.getKey(), "metadata key");
                String value = requireText(entry.getValue(), "metadata value");
                state = state.withVariable(METADATA_VARIABLE_PREFIX + key, value);
            }
            return state;
        };
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }
}
