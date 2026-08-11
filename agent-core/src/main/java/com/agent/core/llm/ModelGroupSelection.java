package com.agent.core.llm;

/** 一次模型请求选择的用户模型组标识。 */
public record ModelGroupSelection(String groupId) {
    public ModelGroupSelection {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("groupId 不能为空");
        }
    }
}
