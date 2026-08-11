package com.agent.core.llm;

import java.util.List;

/** 按精确模型组和任务类型解析运行时端点。 */
@FunctionalInterface
public interface ModelGroupRouteResolver {

    /**
     * 解析一个模型组的有序端点。
     *
     * @param groupId  模型组标识
     * @param taskType 任务类型
     * @return 按降级顺序排列的端点；不存在时返回空列表
     */
    List<ModelEndpoint> resolve(String groupId, TaskType taskType);
}
