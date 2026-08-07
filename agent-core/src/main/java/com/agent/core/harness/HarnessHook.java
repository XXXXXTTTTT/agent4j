package com.agent.core.harness;

/** 节点和工具生命周期的无状态治理 Hook。 */
@FunctionalInterface
public interface HarnessHook {

    /** 处理单个不可变事件。 */
    void onEvent(HarnessEvent event);

    /** 返回 Hook 失败时是否必须终止执行。 */
    default boolean critical() {
        return false;
    }
}
