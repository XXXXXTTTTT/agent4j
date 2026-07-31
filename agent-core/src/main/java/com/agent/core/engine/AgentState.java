package com.agent.core.engine;

import com.agent.core.llm.ChatMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 节点之间传递的不可变工作记忆。
 *
 * @param messages  对话消息
 * @param variables 工作变量
 * @param trace     已执行节点轨迹
 */
public record AgentState(
        List<ChatMessage> messages,
        Map<String, String> variables,
        List<String> trace) {

    /**
     * 创建状态并冻结所有集合。
     */
    public AgentState {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages 不能为空"));
        variables = Map.copyOf(Objects.requireNonNull(variables, "variables 不能为空"));
        trace = List.copyOf(Objects.requireNonNull(trace, "trace 不能为空"));
    }

    /**
     * 创建空工作记忆。
     *
     * @return 空状态
     */
    public static AgentState empty() {
        return new AgentState(List.of(), Map.of(), List.of());
    }

    /**
     * 追加消息并返回新状态。
     *
     * @param message 新消息
     * @return 更新后的状态
     */
    public AgentState withMessage(ChatMessage message) {
        List<ChatMessage> updatedMessages = new ArrayList<>(messages);
        updatedMessages.add(Objects.requireNonNull(message, "message 不能为空"));
        return new AgentState(updatedMessages, variables, trace);
    }

    /**
     * 写入工作变量并返回新状态。
     *
     * @param key   变量名
     * @param value 变量值
     * @return 更新后的状态
     */
    public AgentState withVariable(String key, String value) {
        Map<String, String> updatedVariables = new LinkedHashMap<>(variables);
        updatedVariables.put(
                Objects.requireNonNull(key, "key 不能为空"),
                Objects.requireNonNull(value, "value 不能为空"));
        return new AgentState(messages, updatedVariables, trace);
    }

    /**
     * 追加执行轨迹并返回新状态。
     *
     * @param entry 节点名称
     * @return 更新后的状态
     */
    public AgentState withTraceEntry(String entry) {
        List<String> updatedTrace = new ArrayList<>(trace);
        updatedTrace.add(Objects.requireNonNull(entry, "entry 不能为空"));
        return new AgentState(messages, variables, updatedTrace);
    }
}
