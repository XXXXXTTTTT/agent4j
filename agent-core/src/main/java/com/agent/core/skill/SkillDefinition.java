package com.agent.core.skill;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Skill 的不可变元数据、工具引用与编排策略。 */
public record SkillDefinition(
        String name,
        String version,
        String description,
        List<String> triggers,
        List<String> toolNames,
        String promptFragment) {

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");
    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)");

    /** 校验全部字段并冻结有序列表。 */
    public SkillDefinition {
        requireName(name, "name");
        if (version == null || !VERSION_PATTERN.matcher(version).matches()) {
            throw new IllegalArgumentException("version 必须是无前导零的 MAJOR.MINOR.PATCH");
        }
        requireText(description, "description", 4_000);
        requireText(promptFragment, "promptFragment", 16_000);
        triggers = copyDistinctTexts(triggers, "triggers", false, false);
        toolNames = copyDistinctTexts(toolNames, "toolNames", true, true);
    }

    private static List<String> copyDistinctTexts(
            List<String> values,
            String field,
            boolean requireNonEmpty,
            boolean requireNames) {
        Objects.requireNonNull(values, field + " 不能为空");
        List<String> copy = List.copyOf(values);
        if (requireNonEmpty && copy.isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空列表");
        }
        HashSet<String> seen = new HashSet<>();
        for (String value : copy) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " 不能包含空白值");
            }
            if (requireNames) {
                requireName(value, field + " 元素");
            }
            if (!seen.add(value)) {
                throw new IllegalArgumentException(field + " 不能包含重复值: " + value);
            }
        }
        return copy;
    }

    private static void requireName(String value, String field) {
        if (value == null || !NAME_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " 格式不合法");
        }
    }

    private static void requireText(String value, String field, int maximumCodePoints) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        if (value.codePointCount(0, value.length()) > maximumCodePoints) {
            throw new IllegalArgumentException(field + " 超过长度上限: " + maximumCodePoints);
        }
    }
}
