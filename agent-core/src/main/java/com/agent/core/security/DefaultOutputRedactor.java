package com.agent.core.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

/** 递归复制并脱敏固定敏感字段和值。 */
public final class DefaultOutputRedactor implements OutputRedactor {

    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "apiKey", "authorization", "password", "secret", "token");

    @Override
    public JsonNode redact(String toolName, JsonNode output) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName 不能为空");
        }
        if (output == null) {
            throw new IllegalArgumentException("output 不能为空");
        }
        return redactNode(output, false);
    }

    private JsonNode redactNode(JsonNode node, boolean sensitiveField) {
        if (sensitiveField || (node.isTextual() && isCredential(node.textValue()))) {
            return JsonNodeFactory.instance.textNode("[REDACTED]");
        }
        if (node.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(entry -> copy.set(
                    entry.getKey(), redactNode(entry.getValue(), SENSITIVE_FIELDS.contains(entry.getKey()))));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            node.elements().forEachRemaining(value -> copy.add(redactNode(value, false)));
            return copy;
        }
        return node.deepCopy();
    }

    private static boolean isCredential(String value) {
        return value.startsWith("Bearer ") || value.startsWith("sk-");
    }
}
