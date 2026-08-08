package com.agent.core.skill;

import com.agent.core.tool.ToolDefinition;
import com.agent.core.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** 只读、可发现且按精确触发条件激活的 Skill 目录。 */
public final class SkillCatalog {

    private final Map<String, RegisteredSkill> skills;
    private final List<SkillSummary> summaries;
    private final ObjectMapper objectMapper;

    /** 校验全部 Skill 和 Registry 工具引用后发布不可变目录快照。 */
    public SkillCatalog(
            List<SkillDefinition> definitions,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper) {
        Objects.requireNonNull(definitions, "definitions 不能为空");
        if (definitions.isEmpty()) {
            throw new IllegalArgumentException("definitions 不能为空列表");
        }
        Objects.requireNonNull(toolRegistry, "toolRegistry 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");

        Map<String, RegisteredSkill> registered = new HashMap<>();
        Set<String> triggers = new HashSet<>();
        try {
            for (SkillDefinition definition : List.copyOf(definitions)) {
                if (registered.putIfAbsent(definition.name(), build(definition, toolRegistry)) != null) {
                    throw new SkillRegistrationException(
                            definition.name(), "Skill 名称已注册: " + definition.name(), null);
                }
                for (String trigger : definition.triggers()) {
                    if (!triggers.add(trigger)) {
                        throw new SkillRegistrationException(
                                definition.name(), "Skill trigger 冲突: " + trigger, null);
                    }
                }
            }
        } catch (SkillRegistrationException exception) {
            throw exception;
        } catch (Throwable exception) {
            throw new SkillRegistrationException("", "Skill 目录构造失败", exception);
        }

        this.skills = Map.copyOf(registered);
        this.summaries = this.skills.values().stream()
                .map(RegisteredSkill::summary)
                .sorted(Comparator.comparing(SkillSummary::name))
                .toList();
    }

    /** 返回自然名称顺序的不可变摘要。 */
    public List<SkillSummary> list() {
        return summaries;
    }

    /** 按精确名称执行第三层显式发现。 */
    public Optional<SkillDefinition> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        RegisteredSkill skill = skills.get(name);
        return skill == null ? Optional.empty() : Optional.of(skill.definition());
    }

    /** 根据原始 trigger 和精确点名生成渐进 Prompt 上下文。 */
    public SkillPromptContext resolve(
            String userInput,
            Set<String> explicitlyRequestedNames) {
        Objects.requireNonNull(userInput, "userInput 不能为空");
        Objects.requireNonNull(explicitlyRequestedNames, "explicitlyRequestedNames 不能为空");
        TreeSet<String> activatedNames = new TreeSet<>();
        for (RegisteredSkill skill : orderedSkills()) {
            if (skill.definition().triggers().stream().anyMatch(userInput::contains)) {
                activatedNames.add(skill.definition().name());
            }
        }
        for (String explicitName : explicitlyRequestedNames) {
            if (explicitName == null || explicitName.isBlank() || !skills.containsKey(explicitName)) {
                throw new SkillNotFoundException(explicitName);
            }
            activatedNames.add(explicitName);
        }

        List<ActivatedSkill> activated = activatedNames.stream()
                .map(skills::get)
                .map(RegisteredSkill::activated)
                .toList();
        String discovery = discoverySection();
        String activation = activationSection(activated);
        return new SkillPromptContext(
                discovery,
                activation,
                summaries,
                activated,
                fingerprint(discovery + "\n\n" + activation));
    }

    private RegisteredSkill build(SkillDefinition definition, ToolRegistry toolRegistry) {
        List<SkillToolMetadata> tools = new ArrayList<>(definition.toolNames().size());
        for (String toolName : definition.toolNames()) {
            Optional<ToolDefinition> tool;
            try {
                tool = toolRegistry.find(toolName);
            } catch (Throwable exception) {
                throw new SkillRegistrationException(
                        definition.name(), "读取工具失败: " + toolName, exception);
            }
            if (tool.isEmpty()) {
                throw new SkillRegistrationException(
                        definition.name(), "Skill 引用工具未注册: " + toolName, null);
            }
            ToolDefinition exactTool = tool.get();
            tools.add(new SkillToolMetadata(
                    exactTool.name(), exactTool.description(), exactTool.inputSchema()));
        }
        return new RegisteredSkill(
                definition,
                new SkillSummary(definition.name(), definition.version(), definition.description()),
                List.copyOf(tools));
    }

    private List<RegisteredSkill> orderedSkills() {
        return skills.values().stream()
                .sorted(Comparator.comparing(skill -> skill.definition().name()))
                .toList();
    }

    private String discoverySection() {
        return summaries.stream()
                .map(summary -> "- " + summary.name() + "@" + summary.version() + ": " + summary.description())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String activationSection(List<ActivatedSkill> activated) {
        if (activated.isEmpty()) {
            return "";
        }
        StringBuilder section = new StringBuilder();
        for (ActivatedSkill skill : activated) {
            section.append("## ").append(skill.name()).append('@').append(skill.version()).append('\n');
            section.append("tools:\n");
            for (SkillToolMetadata tool : skill.tools()) {
                section.append("- ").append(tool.name()).append(": ")
                        .append(tool.description()).append("\n")
                        .append("  inputSchema: ").append(canonicalJson(tool.inputSchema())).append('\n');
            }
            section.append("knowledge:\n").append(skill.promptFragment()).append('\n');
        }
        return section.toString();
    }

    private String canonicalJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(canonicalize(node));
        } catch (Exception exception) {
            throw new IllegalStateException("Skill 工具 Schema 渲染失败", exception);
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            java.util.Map<String, JsonNode> values = new java.util.TreeMap<>();
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                values.put(field.getKey(), canonicalize(field.getValue()));
            }
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

    private String fingerprint(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private record RegisteredSkill(
            SkillDefinition definition,
            SkillSummary summary,
            List<SkillToolMetadata> tools) {

        private ActivatedSkill activated() {
            return new ActivatedSkill(definition.name(), definition.version(), tools, definition.promptFragment());
        }
    }
}
