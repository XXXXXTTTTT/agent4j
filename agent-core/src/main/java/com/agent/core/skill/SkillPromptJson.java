package com.agent.core.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.TreeMap;

/** Skill Prompt 中 JSON Schema 的确定性渲染辅助类。 */
public final class SkillPromptJson {

    private SkillPromptJson() {
    }

    /** 按对象键排序并保留数组顺序后渲染 JSON。 */
    public static String canonicalJson(ObjectMapper objectMapper, JsonNode node) {
        try {
            return objectMapper.writeValueAsString(canonicalize(node));
        } catch (Exception exception) {
            throw new IllegalStateException("Skill 工具 Schema 渲染失败", exception);
        }
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            TreeMap<String, JsonNode> values = new TreeMap<>();
            node.fields().forEachRemaining(field -> values.put(field.getKey(), canonicalize(field.getValue())));
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            values.forEach(result::set);
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            node.elements().forEachRemaining(value -> result.add(canonicalize(value)));
            return result;
        }
        return node.deepCopy();
    }
}
