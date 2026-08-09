package com.agent.core.security;

import com.agent.core.tool.ToolCall;
import com.agent.core.tool.ToolDefinition;
import com.agent.core.tool.ToolInvocationContext;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 基于精确工具名和 JSON Pointer 白名单的确定性参数策略。 */
public final class DefaultToolParameterPolicy implements ToolParameterPolicy {

    private final Map<String, Set<String>> allowedPointers;

    public DefaultToolParameterPolicy(Map<String, Set<String>> allowedPointers) {
        Objects.requireNonNull(allowedPointers, "allowedPointers 不能为空");
        this.allowedPointers = allowedPointers.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        entry -> requireText(entry.getKey(), "toolName"),
                        entry -> Set.copyOf(Objects.requireNonNull(
                                entry.getValue(), "JSON Pointer 集合不能为空"))));
    }

    /** 返回拒绝未知参数、控制字符和凭据格式的确定性结果。 */
    @Override
    public ToolParameterDecision inspect(
            ToolDefinition definition,
            ToolCall call,
            ToolInvocationContext context) {
        Objects.requireNonNull(definition, "definition 不能为空");
        Objects.requireNonNull(call, "call 不能为空");
        Objects.requireNonNull(context, "context 不能为空");
        Set<String> pointers = allowedPointers.get(definition.name());
        if (pointers == null) {
            return block("security.tool-parameter-rule-missing", "工具未声明参数安全策略");
        }
        List<String> paths = new ArrayList<>();
        collectLeafPointers(call.arguments(), "", paths);
        Set<String> unknown = new HashSet<>(paths);
        unknown.removeAll(pointers);
        if (!unknown.isEmpty()) {
            return block("security.tool-parameter-pointer-denied", "工具参数包含未声明字段");
        }
        for (String path : paths) {
            JsonNode value = call.arguments().at(path);
            if (value.isTextual()) {
                String text = value.textValue();
                if (containsControlCharacter(text)) {
                    return block("security.tool-parameter-control-character", "工具参数包含控制字符");
                }
                if (text.startsWith("Bearer ") || text.startsWith("sk-")) {
                    return block("security.tool-parameter-credential-format", "工具参数包含凭据格式");
                }
            }
        }
        return new ToolParameterDecision(SecurityDecision.ALLOW, "", "");
    }

    private static ToolParameterDecision block(String ruleId, String summary) {
        return new ToolParameterDecision(SecurityDecision.BLOCK, ruleId, summary);
    }

    private static void collectLeafPointers(JsonNode node, String pointer, List<String> result) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectLeafPointers(
                    entry.getValue(), pointer + "/" + escape(entry.getKey()), result));
            return;
        }
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                collectLeafPointers(node.get(index), pointer + "/" + index, result);
            }
            return;
        }
        result.add(pointer);
    }

    private static String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static boolean containsControlCharacter(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                && codePoint != '\t');
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }
}
