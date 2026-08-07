package com.agent.core.prompt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 按精确名称和版本注册、渲染 Prompt 的进程内目录。 */
public final class PromptCatalog {

    private final Map<PromptKey, PromptTemplate> templates;

    /** 注册全部模板并拒绝重复名称与版本。 */
    public PromptCatalog(List<PromptTemplate> templates) {
        Objects.requireNonNull(templates, "templates 不能为空");
        if (templates.isEmpty()) {
            throw new IllegalArgumentException("templates 不能为空列表");
        }
        Map<PromptKey, PromptTemplate> registered = new LinkedHashMap<>();
        for (PromptTemplate template : templates) {
            PromptTemplate exactTemplate = Objects.requireNonNull(
                    template, "templates 不能包含 null");
            PromptKey key = new PromptKey(exactTemplate.name(), exactTemplate.version());
            if (registered.putIfAbsent(key, exactTemplate) != null) {
                throw new IllegalArgumentException("Prompt 已注册: " + key.display());
            }
        }
        this.templates = Map.copyOf(registered);
    }

    /** 渲染指定名称与版本，并生成稳定 SHA-256 指纹。 */
    public RenderedPrompt render(
            String name,
            String version,
            Map<String, String> variables) {
        PromptKey key = new PromptKey(name, version);
        PromptTemplate template = templates.get(key);
        if (template == null) {
            throw new IllegalArgumentException("Prompt 未注册: " + key.display());
        }
        Objects.requireNonNull(variables, "variables 不能为空");
        String dynamicSection = template.dynamicTemplate();
        for (String variable : template.requiredVariables()) {
            String value = variables.get(variable);
            if (value == null) {
                throw new IllegalArgumentException("缺少 Prompt 变量: " + variable);
            }
            dynamicSection = dynamicSection.replace("{{" + variable + "}}", value);
        }
        String fingerprint = fingerprint(
                template.name()
                        + "\n" + template.version()
                        + "\n" + template.staticSection()
                        + "\n" + dynamicSection);
        return new RenderedPrompt(
                template.name(),
                template.version(),
                template.staticSection(),
                dynamicSection,
                fingerprint);
    }

    private String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private record PromptKey(String name, String version) {
        private PromptKey {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name 不能为空");
            }
            if (version == null || version.isBlank()) {
                throw new IllegalArgumentException("version 不能为空");
            }
        }

        private String display() {
            return name + "@" + version;
        }
    }
}
