package com.agent.core.engine;

/** 创建一次执行专用的独立状态图。 */
@FunctionalInterface
public interface GraphFactory {

    /**
     * 创建新的状态图实例。
     *
     * @return 独立状态图
     */
    StateGraph create();
}
