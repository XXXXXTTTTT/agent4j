package com.agent.core.llm;

import java.util.Objects;

/** 一条任务路由的全部模型端点均失败。 */
public final class ModelRoutingException extends RuntimeException {

    /**
     * 创建包含精确任务类型的路由异常。
     *
     * @param taskType 任务类型
     */
    public ModelRoutingException(TaskType taskType) {
        super("模型路由全部失败: taskType="
                + Objects.requireNonNull(taskType, "taskType 不能为空"));
    }
}
