package com.agent.web.skill;

import com.agent.core.skill.SkillDefinition;
import com.agent.core.security.DefaultPromptInjectionDetector;
import com.agent.core.security.PromptSecurityAssessment;
import com.agent.core.security.PromptSecurityContext;
import com.agent.core.security.SecurityDecision;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 只解析本期允许的 SKILL.md front matter，不赋予外部内容任何执行权。 */
final class GitHubSkillContent {

    private static final Set<String> ALLOWED_FIELDS = Set.of("name", "version", "description", "triggers", "tools");

    private final SkillDefinition definition;
    private final String summary;
    private final List<String> requestedToolNames;

    private GitHubSkillContent(SkillDefinition definition) {
        this.definition = definition;
        this.summary = definition.description();
        this.requestedToolNames = definition.toolNames();
    }

    static GitHubSkillContent parse(String source, Set<String> registeredToolNames) {
        Objects.requireNonNull(source, "source 不能为空");
        Objects.requireNonNull(registeredToolNames, "registeredToolNames 不能为空");
        ParsedFrontMatter parsed = parseFrontMatter(source);
        for (String field : parsed.values().keySet()) {
            if (!ALLOWED_FIELDS.contains(field)) {
                throw new IllegalArgumentException("未知 Skill front matter 字段: " + field);
            }
        }
        String name = requiredScalar(parsed.values(), "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Skill name 不能为空");
        }
        String version = requiredScalar(parsed.values(), "version");
        String description = requiredScalar(parsed.values(), "description");
        List<String> triggers = strings(parsed.values(), "triggers", true);
        List<String> requestedTools = tools(parsed.values());
        for (String toolName : requestedTools) {
            if (!registeredToolNames.contains(toolName)) {
                throw new IllegalArgumentException("Skill 声明了未注册工具: " + toolName);
            }
        }
        PromptSecurityAssessment assessment = new DefaultPromptInjectionDetector().inspect(
                new PromptSecurityContext(UUID.randomUUID(), "github-skill", "skill-install", "tool.output"),
                parsed.body());
        if (assessment.decision() != SecurityDecision.ALLOW) {
            throw new IllegalArgumentException("Skill 内容未通过安全检查: "
                    + assessment.findings().getFirst().ruleId());
        }
        return new GitHubSkillContent(new SkillDefinition(
                name, version, description, triggers, requestedTools, parsed.body()));
    }

    String summary() {
        return summary;
    }

    List<String> requestedToolNames() {
        return requestedToolNames;
    }

    SkillDefinition definition() {
        return definition;
    }

    private static ParsedFrontMatter parseFrontMatter(String source) {
        List<String> lines = source.lines().toList();
        if (lines.isEmpty() || !"---".equals(lines.getFirst())) {
            throw new IllegalArgumentException("SKILL.md 必须以 front matter 开始");
        }
        int end = -1;
        for (int index = 1; index < lines.size(); index++) {
            if ("---".equals(lines.get(index))) {
                end = index;
                break;
            }
        }
        if (end < 0) {
            throw new IllegalArgumentException("SKILL.md front matter 缺少结束标记");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        String activeList = null;
        for (int index = 1; index < end; index++) {
            String line = lines.get(index);
            if (line.startsWith("  - ")) {
                if (activeList == null) {
                    throw new IllegalArgumentException("Skill front matter 列表缺少字段");
                }
                @SuppressWarnings("unchecked")
                List<String> list = (List<String>) values.get(activeList);
                list.add(requireText(line.substring(4), "Skill front matter 列表项"));
                continue;
            }
            int separator = line.indexOf(':');
            if (separator <= 0 || line.startsWith(" ")) {
                throw new IllegalArgumentException("Skill front matter 格式不合法");
            }
            String key = requireText(line.substring(0, separator), "Skill front matter 字段");
            String value = line.substring(separator + 1).strip();
            if (values.containsKey(key)) {
                throw new IllegalArgumentException("Skill front matter 字段重复: " + key);
            }
            if (value.isEmpty()) {
                values.put(key, new ArrayList<String>());
                activeList = key;
            } else {
                values.put(key, value);
                activeList = null;
            }
        }
        String body = String.join("\n", lines.subList(end + 1, lines.size())).strip();
        if (body.isEmpty()) {
            throw new IllegalArgumentException("SKILL.md 正文不能为空");
        }
        return new ParsedFrontMatter(Map.copyOf(values), body);
    }

    private static String requiredScalar(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Skill front matter 缺少文本字段: " + key);
        }
        return text;
    }

    private static List<String> tools(Map<String, Object> values) {
        Object value = values.get("tools");
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> rawValues)) {
            throw new IllegalArgumentException("Skill front matter tools 必须是列表");
        }
        LinkedHashSet<String> valuesSet = new LinkedHashSet<>();
        for (Object raw : rawValues) {
            if (!(raw instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException("Skill front matter tools 包含空值");
            }
            if (!valuesSet.add(text)) {
                throw new IllegalArgumentException("Skill front matter tools 包含重复值: " + text);
            }
        }
        return List.copyOf(valuesSet);
    }

    private static List<String> strings(Map<String, Object> values, String key, boolean required) {
        Object value = values.get(key);
        if (!(value instanceof List<?> rawValues)) {
            if (!required && value == null) return List.of();
            throw new IllegalArgumentException("Skill front matter " + key + " 必须是列表");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object raw : rawValues) {
            if (!(raw instanceof String text) || text.isBlank() || !result.add(text)) {
                throw new IllegalArgumentException("Skill front matter " + key + " 包含非法或重复值");
            }
        }
        return List.copyOf(result);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    private record ParsedFrontMatter(Map<String, Object> values, String body) {
    }
}
