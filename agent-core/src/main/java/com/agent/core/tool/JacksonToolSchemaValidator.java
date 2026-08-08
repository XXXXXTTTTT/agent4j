package com.agent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** 使用 Jackson 实现受控且确定性的 JSON Schema 子集。 */
public final class JacksonToolSchemaValidator implements ToolSchemaValidator {

    private static final Set<String> TYPES = Set.of(
            "object", "string", "integer", "number", "boolean", "array");
    private static final Set<String> COMMON = Set.of(
            "type", "enum", "title", "description");
    private static final Map<String, Set<String>> TYPE_KEYWORDS = Map.of(
            "object", Set.of("properties", "required", "additionalProperties"),
            "string", Set.of("minLength", "maxLength"),
            "integer", Set.of("minimum", "maximum"),
            "number", Set.of("minimum", "maximum"),
            "boolean", Set.of(),
            "array", Set.of("items"));

    /** 校验根 object Schema 和全部嵌套定义。 */
    @Override
    public void validateSchema(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            throw failure("/", "Schema 必须是 JSON object");
        }
        validateSchemaNode(schema, "/", true);
    }

    /** 先校验 Schema，再递归校验参数。 */
    @Override
    public void validateArguments(JsonNode schema, JsonNode arguments) {
        validateSchema(schema);
        if (arguments == null) {
            throw failure("/", "arguments 不能为空");
        }
        validateValue(schema, arguments, "/");
    }

    private void validateSchemaNode(JsonNode schema, String pointer, boolean root) {
        if (!schema.isObject()) {
            throw failure(pointer, "Schema 节点必须是 JSON object");
        }
        JsonNode typeNode = schema.get("type");
        if (typeNode == null || !typeNode.isTextual() || !TYPES.contains(typeNode.textValue())) {
            throw failure(child(pointer, "type"), "type 不受支持");
        }
        String type = typeNode.textValue();
        if (root && !"object".equals(type)) {
            throw failure(child(pointer, "type"), "根 Schema 的 type 必须是 object");
        }
        Set<String> allowed = new HashSet<>(COMMON);
        allowed.addAll(TYPE_KEYWORDS.get(type));
        schema.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw failure(child(pointer, field), "Schema 关键字不受支持: " + field);
            }
        });
        validateTextAnnotation(schema, "title", pointer);
        validateTextAnnotation(schema, "description", pointer);
        validateEnum(schema, pointer, type);

        switch (type) {
            case "object" -> validateObjectSchema(schema, pointer);
            case "string" -> validateStringSchema(schema, pointer);
            case "integer", "number" -> validateNumberSchema(schema, pointer);
            case "array" -> validateArraySchema(schema, pointer);
            case "boolean" -> { }
            default -> throw failure(child(pointer, "type"), "type 不受支持");
        }
    }

    private void validateObjectSchema(JsonNode schema, String pointer) {
        JsonNode properties = schema.get("properties");
        if (properties != null && !properties.isObject()) {
            throw failure(child(pointer, "properties"), "properties 必须是 object");
        }
        if (properties != null) {
            Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String propertyPointer = child(child(pointer, "properties"), field.getKey());
                if (field.getKey().isBlank()) {
                    throw failure(propertyPointer, "属性名不能为空");
                }
                validateSchemaNode(field.getValue(), propertyPointer, false);
            }
        }
        JsonNode required = schema.get("required");
        if (required != null) {
            if (!required.isArray()) {
                throw failure(child(pointer, "required"), "required 必须是字符串数组");
            }
            Set<String> names = new HashSet<>();
            for (int index = 0; index < required.size(); index++) {
                JsonNode name = required.get(index);
                String itemPointer = child(child(pointer, "required"), String.valueOf(index));
                if (!name.isTextual() || name.textValue().isBlank()) {
                    throw failure(itemPointer, "required 元素必须是非空字符串");
                }
                if (!names.add(name.textValue())) {
                    throw failure(itemPointer, "required 不能包含重复字段");
                }
                if (properties == null || !properties.has(name.textValue())) {
                    throw failure(itemPointer, "required 字段必须在 properties 中声明");
                }
            }
        }
        JsonNode additionalProperties = schema.get("additionalProperties");
        if (additionalProperties != null && !additionalProperties.isBoolean()) {
            throw failure(child(pointer, "additionalProperties"),
                    "additionalProperties 必须是 boolean");
        }
    }

    private void validateStringSchema(JsonNode schema, String pointer) {
        Integer minimum = nonNegativeInteger(schema, "minLength", pointer);
        Integer maximum = nonNegativeInteger(schema, "maxLength", pointer);
        if (minimum != null && maximum != null && minimum > maximum) {
            throw failure(child(pointer, "minLength"), "minLength 不能大于 maxLength");
        }
    }

    private void validateNumberSchema(JsonNode schema, String pointer) {
        BigDecimal minimum = finiteDecimal(schema, "minimum", pointer);
        BigDecimal maximum = finiteDecimal(schema, "maximum", pointer);
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw failure(child(pointer, "minimum"), "minimum 不能大于 maximum");
        }
    }

    private void validateArraySchema(JsonNode schema, String pointer) {
        JsonNode items = schema.get("items");
        if (items == null) {
            throw failure(child(pointer, "items"), "array Schema 必须声明 items");
        }
        validateSchemaNode(items, child(pointer, "items"), false);
    }

    private void validateEnum(JsonNode schema, String pointer, String type) {
        JsonNode values = schema.get("enum");
        if (values == null) {
            return;
        }
        if (!values.isArray() || values.isEmpty()) {
            throw failure(child(pointer, "enum"), "enum 必须是非空数组");
        }
        for (int index = 0; index < values.size(); index++) {
            if (!matchesType(type, values.get(index))) {
                throw failure(child(child(pointer, "enum"), String.valueOf(index)),
                        "enum 元素与 type 不一致");
            }
        }
    }

    private void validateValue(JsonNode schema, JsonNode value, String pointer) {
        String type = schema.path("type").textValue();
        if (!matchesType(type, value) || isNonFinite(value)) {
            throw failure(pointer, "参数类型与 Schema 不一致");
        }
        JsonNode enumValues = schema.get("enum");
        if (enumValues != null && !contains(enumValues, value)) {
            throw failure(pointer, "参数不在 enum 中");
        }
        switch (type) {
            case "object" -> validateObjectValue(schema, value, pointer);
            case "string" -> validateStringValue(schema, value, pointer);
            case "integer", "number" -> validateNumberValue(schema, value, pointer);
            case "array" -> validateArrayValue(schema, value, pointer);
            case "boolean" -> { }
            default -> throw failure(pointer, "参数类型与 Schema 不一致");
        }
    }

    private void validateObjectValue(JsonNode schema, JsonNode value, String pointer) {
        JsonNode required = schema.get("required");
        if (required != null) {
            for (JsonNode name : required) {
                if (!value.has(name.textValue())) {
                    throw failure(child(pointer, name.textValue()), "缺少 required 参数");
                }
            }
        }
        JsonNode properties = schema.get("properties");
        boolean allowAdditional = !schema.has("additionalProperties")
                || schema.path("additionalProperties").booleanValue();
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode propertySchema = properties == null ? null : properties.get(field.getKey());
            if (propertySchema == null) {
                if (!allowAdditional) {
                    throw failure(child(pointer, field.getKey()), "参数字段未声明");
                }
            } else {
                validateValue(propertySchema, field.getValue(), child(pointer, field.getKey()));
            }
        }
    }

    private void validateStringValue(JsonNode schema, JsonNode value, String pointer) {
        int length = value.textValue().codePointCount(0, value.textValue().length());
        if (schema.has("minLength") && length < schema.path("minLength").intValue()) {
            throw failure(pointer, "字符串长度小于 minLength");
        }
        if (schema.has("maxLength") && length > schema.path("maxLength").intValue()) {
            throw failure(pointer, "字符串长度大于 maxLength");
        }
    }

    private void validateNumberValue(JsonNode schema, JsonNode value, String pointer) {
        BigDecimal number = value.decimalValue();
        if (schema.has("minimum")
                && number.compareTo(schema.path("minimum").decimalValue()) < 0) {
            throw failure(pointer, "数值小于 minimum");
        }
        if (schema.has("maximum")
                && number.compareTo(schema.path("maximum").decimalValue()) > 0) {
            throw failure(pointer, "数值大于 maximum");
        }
    }

    private void validateArrayValue(JsonNode schema, JsonNode value, String pointer) {
        JsonNode items = schema.get("items");
        for (int index = 0; index < value.size(); index++) {
            validateValue(items, value.get(index), child(pointer, String.valueOf(index)));
        }
    }

    private void validateTextAnnotation(JsonNode schema, String field, String pointer) {
        JsonNode value = schema.get(field);
        if (value != null && (!value.isTextual() || value.textValue().isBlank())) {
            throw failure(child(pointer, field), field + " 必须是非空字符串");
        }
    }

    private Integer nonNegativeInteger(JsonNode schema, String field, String pointer) {
        JsonNode value = schema.get(field);
        if (value == null) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw failure(child(pointer, field), field + " 必须是非负整数");
        }
        return value.intValue();
    }

    private BigDecimal finiteDecimal(JsonNode schema, String field, String pointer) {
        JsonNode value = schema.get(field);
        if (value == null) {
            return null;
        }
        if (!value.isNumber() || isNonFinite(value)) {
            throw failure(child(pointer, field), field + " 必须是有限数值");
        }
        return value.decimalValue();
    }

    private boolean matchesType(String type, JsonNode value) {
        return switch (type) {
            case "object" -> value.isObject();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            default -> false;
        };
    }

    private boolean isNonFinite(JsonNode value) {
        return value.isFloatingPointNumber() && !Double.isFinite(value.doubleValue());
    }

    private boolean contains(JsonNode array, JsonNode expected) {
        for (JsonNode value : array) {
            if (value.equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private ToolSchemaException failure(String pointer, String message) {
        return new ToolSchemaException(pointer, message, null);
    }

    private String child(String pointer, String token) {
        String escaped = token.replace("~", "~0").replace("/", "~1");
        return "/".equals(pointer) ? "/" + escaped : pointer + "/" + escaped;
    }
}
