package com.agent.core.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Objects;

/**
 * 不含模型名的路由请求。
 *
 * @param messages    对话消息
 * @param tools       可调用工具
 * @param toolChoice  工具选择策略
 * @param temperature 采样温度
 */
public record ModelRequest(
        List<ChatMessage> messages,
        List<LlmClient.Tool> tools,
        JsonNode toolChoice,
        Double temperature) {

    /** 冻结请求集合。 */
    public ModelRequest {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages 不能为空"));
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
