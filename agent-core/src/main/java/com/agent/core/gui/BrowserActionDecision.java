package com.agent.core.gui;

import com.agent.core.tool.builtin.BrowserToolDefinitions;
import com.agent.sandbox.browser.BrowserEvidenceSelector;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 视觉模型返回的严格浏览器动作。 */
public record BrowserActionDecision(
        Action action,
        String selector,
        String value,
        int deltaY,
        String evidenceSelector,
        String reason,
        String summary,
        List<String> evidenceRefs) {

    /** 校验动作拥有的字段并冻结证据引用。 */
    public BrowserActionDecision {
        Objects.requireNonNull(action, "action 不能为空");
        selector = Objects.requireNonNull(selector, "selector 不能为空");
        value = Objects.requireNonNull(value, "value 不能为空");
        evidenceSelector = Objects.requireNonNull(
                evidenceSelector, "evidenceSelector 不能为空");
        reason = requireText(reason, "reason");
        summary = Objects.requireNonNull(summary, "summary 不能为空");
        evidenceRefs = List.copyOf(Objects.requireNonNull(
                evidenceRefs, "evidenceRefs 不能为空"));
        validateSelectorLength(selector, "selector");
        if (!evidenceSelector.isEmpty()) {
            BrowserEvidenceSelector.locator(evidenceSelector);
        }
        validateReferences(evidenceRefs);
        switch (action) {
            case CLICK -> {
                requireActionSelector(selector);
                requireEmpty(value, "click 的 value");
                requireZero(deltaY, "click 的 deltaY");
                requireEvidenceSelector(evidenceSelector);
                requireEmpty(summary, "click 的 summary");
                requireNoEvidenceRefs(evidenceRefs, "click");
            }
            case FILL -> {
                requireActionSelector(selector);
                requireZero(deltaY, "fill 的 deltaY");
                requireEvidenceSelector(evidenceSelector);
                requireEmpty(summary, "fill 的 summary");
                requireNoEvidenceRefs(evidenceRefs, "fill");
            }
            case SCROLL -> {
                requireEmpty(selector, "scroll 的 selector");
                requireEmpty(value, "scroll 的 value");
                if (deltaY == 0 || Math.abs((long) deltaY)
                        > BrowserToolDefinitions.MAX_SCROLL_DELTA) {
                    throw new IllegalArgumentException("scroll 的 deltaY 超出允许范围");
                }
                requireEvidenceSelector(evidenceSelector);
                requireEmpty(summary, "scroll 的 summary");
                requireNoEvidenceRefs(evidenceRefs, "scroll");
            }
            case DONE -> {
                requireEmpty(selector, "done 的 selector");
                requireEmpty(value, "done 的 value");
                requireZero(deltaY, "done 的 deltaY");
                requireEmpty(evidenceSelector, "done 的 evidenceSelector");
                requireText(summary, "summary");
                if (evidenceRefs.isEmpty()) {
                    throw new IllegalArgumentException("done 的 evidenceRefs 不能为空");
                }
            }
        }
    }

    /** 从不带 Markdown 包装的完整 JSON 文档严格解析动作。 */
    public static BrowserActionDecision parse(ObjectMapper objectMapper, String json)
            throws IOException {
        Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("动作 JSON 不能为空");
        }
        JsonNode root = objectMapper.readTree(json);
        validateJsonTypes(root);
        try {
            return objectMapper.readerFor(BrowserActionDecision.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(json);
        } catch (JsonMappingException exception) {
            if (exception.getCause() instanceof IllegalArgumentException argumentException) {
                throw argumentException;
            }
            throw exception;
        }
    }

    private static void validateJsonTypes(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("浏览器动作必须是 JSON 对象");
        }
        requireType(root, "action", JsonNode::isTextual, "string");
        requireType(root, "selector", JsonNode::isTextual, "string");
        requireType(root, "value", JsonNode::isTextual, "string");
        requireType(root, "deltaY",
                node -> node.isIntegralNumber() && node.canConvertToInt(), "int");
        requireType(root, "evidenceSelector", JsonNode::isTextual, "string");
        requireType(root, "reason", JsonNode::isTextual, "string");
        requireType(root, "summary", JsonNode::isTextual, "string");
        requireType(root, "evidenceRefs", JsonNode::isArray, "string array");
        for (JsonNode evidenceRef : root.path("evidenceRefs")) {
            if (!evidenceRef.isTextual()) {
                throw new IllegalArgumentException("evidenceRefs 元素必须是 string");
            }
        }
    }

    private static void requireType(
            JsonNode root,
            String field,
            java.util.function.Predicate<JsonNode> predicate,
            String expected) {
        JsonNode value = root.get(field);
        if (value == null || !predicate.test(value)) {
            throw new IllegalArgumentException(field + " 必须是 " + expected);
        }
    }

    private static void validateReferences(List<String> references) {
        HashSet<String> unique = new HashSet<>();
        for (String reference : references) {
            requireText(reference, "evidenceRefs 元素");
            if (!unique.add(reference)) {
                throw new IllegalArgumentException("evidenceRefs 不能包含重复引用");
            }
        }
    }

    private static void validateSelectorLength(String selector, String field) {
        if (selector.codePointCount(0, selector.length())
                > BrowserEvidenceSelector.MAX_LOCATOR_CODE_POINTS) {
            throw new IllegalArgumentException(field + " 超过长度上限");
        }
    }

    private static void requireActionSelector(String selector) {
        if (selector.isBlank()) {
            throw new IllegalArgumentException("selector 不能为空");
        }
    }

    private static void requireEvidenceSelector(String selector) {
        if (selector.isBlank()) {
            throw new IllegalArgumentException("evidenceSelector 不能为空");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    private static void requireEmpty(String value, String field) {
        if (!value.isEmpty()) {
            throw new IllegalArgumentException(field + " 必须为空");
        }
    }

    private static void requireZero(int value, String field) {
        if (value != 0) {
            throw new IllegalArgumentException(field + " 必须为 0");
        }
    }

    private static void requireNoEvidenceRefs(List<String> references, String action) {
        if (!references.isEmpty()) {
            throw new IllegalArgumentException(action + " 的 evidenceRefs 必须为空");
        }
    }

    /** 浏览器动作协议值。 */
    public enum Action {
        CLICK("click"),
        FILL("fill"),
        SCROLL("scroll"),
        DONE("done");

        private final String jsonValue;

        Action(String jsonValue) {
            this.jsonValue = jsonValue;
        }

        /** 返回精确 JSON 协议值。 */
        @JsonValue
        public String jsonValue() {
            return jsonValue;
        }

        /** 解析精确 JSON 协议值。 */
        @JsonCreator
        public static Action fromJson(String value) {
            for (Action action : values()) {
                if (action.jsonValue.equals(value)) {
                    return action;
                }
            }
            throw new IllegalArgumentException("未知浏览器动作: " + value);
        }
    }
}
