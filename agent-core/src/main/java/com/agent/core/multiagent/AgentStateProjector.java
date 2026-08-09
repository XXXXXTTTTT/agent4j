package com.agent.core.multiagent;

import com.agent.core.engine.AgentState;
import com.agent.core.llm.ChatMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 按 Agent 状态权限投影子状态并原子校验合并结果。 */
public final class AgentStateProjector {

    public AgentState project(
            AgentState parentState,
            AgentDescriptor target,
            AgentHandoff handoff) {
        Objects.requireNonNull(parentState, "parentState 不能为空");
        validateTarget(target, handoff);

        Map<String, String> variables = new LinkedHashMap<>();
        for (String key : target.readableStateKeys()) {
            String value = parentState.variables().get(key);
            if (value == null) {
                throw new AgentHandoffStateException(
                        key, "父状态缺少目标 Agent 的只读输入: " + key);
            }
            variables.put(key, value);
        }

        List<ChatMessage> messages = new ArrayList<>();
        if (handoff.contextMode() == HandoffContextMode.FORK) {
            messages.addAll(parentState.messages());
        }
        messages.add(ChatMessage.user(handoff.content()));
        return new AgentState(messages, variables, List.of());
    }

    public AgentState merge(
            AgentState parentState,
            AgentState initialChildState,
            AgentState finalChildState,
            AgentDescriptor target,
            AgentHandoff handoff,
            UUID childRunId) {
        Objects.requireNonNull(parentState, "parentState 不能为空");
        Objects.requireNonNull(initialChildState, "initialChildState 不能为空");
        Objects.requireNonNull(finalChildState, "finalChildState 不能为空");
        Objects.requireNonNull(childRunId, "childRunId 不能为空");
        validateTarget(target, handoff);
        validateInitialKeys(initialChildState, target);
        validateFinalState(initialChildState, finalChildState, target, handoff);

        for (String key : handoff.requestedOutputKeys()) {
            String childValue = finalChildState.variables().get(key);
            String parentValue = parentState.variables().get(key);
            if (parentValue != null && !parentValue.equals(childValue)) {
                throw new AgentStateMergeException(key);
            }
        }

        AgentState merged = parentState;
        for (String key : handoff.requestedOutputKeys()) {
            String value = finalChildState.variables().get(key);
            if (!value.equals(parentState.variables().get(key))) {
                merged = merged.withVariable(key, value);
            }
        }
        return merged.withTraceEntry(
                "handoff:" + handoff.taskId() + ":" + target.agentId() + ":" + childRunId);
    }

    private void validateInitialKeys(
            AgentState initialChildState,
            AgentDescriptor target) {
        Set<String> actual = initialChildState.variables().keySet();
        if (!actual.equals(target.readableStateKeys())) {
            Set<String> unexpected = new LinkedHashSet<>(actual);
            unexpected.removeAll(target.readableStateKeys());
            if (!unexpected.isEmpty()) {
                String key = unexpected.iterator().next();
                throw new AgentHandoffStateException(
                        key, "初始子状态包含未授权变量: " + key);
            }
            Set<String> missing = new LinkedHashSet<>(target.readableStateKeys());
            missing.removeAll(actual);
            String key = missing.iterator().next();
            throw new AgentHandoffStateException(
                    key, "初始子状态缺少只读变量: " + key);
        }
    }

    private void validateFinalState(
            AgentState initialChildState,
            AgentState finalChildState,
            AgentDescriptor target,
            AgentHandoff handoff) {
        for (String key : target.readableStateKeys()) {
            String initialValue = initialChildState.variables().get(key);
            String finalValue = finalChildState.variables().get(key);
            if (!Objects.equals(initialValue, finalValue)) {
                throw new AgentHandoffStateException(
                        key, "子运行修改或删除了只读状态键: " + key);
            }
        }

        Set<String> allowed = new LinkedHashSet<>(target.readableStateKeys());
        allowed.addAll(target.ownedStateKeys());
        for (String key : finalChildState.variables().keySet()) {
            if (!allowed.contains(key)) {
                throw new AgentHandoffStateException(
                        key, "子运行写入未拥有的状态键: " + key);
            }
        }

        for (String key : handoff.requestedOutputKeys()) {
            if (!target.ownedStateKeys().contains(key)) {
                throw new AgentHandoffStateException(
                        key, "请求输出键不属于目标 Agent: " + key);
            }
            if (!finalChildState.variables().containsKey(key)) {
                throw new AgentHandoffStateException(
                        key, "子运行缺少请求输出键: " + key);
            }
        }
    }

    private void validateTarget(AgentDescriptor target, AgentHandoff handoff) {
        Objects.requireNonNull(target, "target 不能为空");
        Objects.requireNonNull(handoff, "handoff 不能为空");
        if (!target.agentId().equals(handoff.toAgent())) {
            throw new IllegalArgumentException(
                    "目标描述与 Handoff 不一致: descriptor="
                            + target.agentId()
                            + ", handoff="
                            + handoff.toAgent());
        }
    }
}
