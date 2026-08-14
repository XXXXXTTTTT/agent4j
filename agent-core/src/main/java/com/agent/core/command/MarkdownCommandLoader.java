package com.agent.core.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** 从全局和工作区目录安全加载 Markdown 工作流命令。 */
public final class MarkdownCommandLoader {

    private static final Set<String> FRONT_MATTER_FIELDS = Set.of(
            "name", "description", "channel", "aliases", "arguments", "permission");
    private final long maxFileBytes;
    private final WorkflowCommandBridge workflowBridge;
    private final ObjectMapper yamlMapper;
    private final CommandTemplateRenderer renderer = new CommandTemplateRenderer();

    /** 创建带文件大小上限的加载器。 */
    public MarkdownCommandLoader(long maxFileBytes, WorkflowCommandBridge workflowBridge) {
        if (maxFileBytes < 1) {
            throw new IllegalArgumentException("maxFileBytes 必须大于 0");
        }
        this.maxFileBytes = maxFileBytes;
        this.workflowBridge = Objects.requireNonNull(workflowBridge, "workflowBridge 不能为空");
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    /** 加载全局目录和工作区目录中的 Markdown 命令。 */
    public List<CommandDefinition> load(Path globalDirectory, Path workspaceDirectory) {
        List<CommandDefinition> definitions = new ArrayList<>();
        loadRoot(globalDirectory, CommandSource.GLOBAL, definitions);
        loadRoot(workspaceDirectory, CommandSource.WORKSPACE, definitions);
        validateSourceDuplicates(definitions);
        return List.copyOf(definitions);
    }

    private void loadRoot(Path directory, CommandSource source, List<CommandDefinition> target) {
        if (directory == null || !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(directory)) {
            throw new MarkdownCommandLoadException("命令目录不能是符号链接: " + directory);
        }
        final Path root;
        try {
            root = directory.toRealPath();
        } catch (IOException exception) {
            throw new MarkdownCommandLoadException("无法解析命令目录: " + directory, exception);
        }
        try (var paths = Files.list(root)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .forEach(path -> loadFile(root, path, source, target));
        } catch (IOException exception) {
            throw new MarkdownCommandLoadException("无法读取命令目录: " + root, exception);
        }
    }

    private void loadFile(Path root, Path path, CommandSource source, List<CommandDefinition> target) {
        if (Files.isSymbolicLink(path)) {
            throw new MarkdownCommandLoadException("命令文件不能是符号链接: " + path);
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Path realPath = path.toRealPath();
            if (!realPath.startsWith(root)) {
                throw new MarkdownCommandLoadException("命令文件越出目录边界: " + path);
            }
            if (Files.size(realPath) > maxFileBytes) {
                throw new MarkdownCommandLoadException("命令文件大小超过限制: " + path);
            }
            String content = Files.readString(realPath, StandardCharsets.UTF_8);
            target.add(parse(path, content, source));
        } catch (MarkdownCommandLoadException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new MarkdownCommandLoadException("无法加载命令文件: " + path, exception);
        }
    }

    private CommandDefinition parse(Path path, String content, CommandSource source) {
        String[] lines = content.split("\\R", -1);
        if (lines.length == 0 || !"---".equals(lines[0].trim())) {
            throw new MarkdownCommandLoadException("缺少 front matter: " + path);
        }
        int closing = -1;
        for (int index = 1; index < lines.length; index++) {
            if ("---".equals(lines[index].trim())) {
                closing = index;
                break;
            }
        }
        if (closing < 0) {
            throw new MarkdownCommandLoadException("front matter 未闭合: " + path);
        }
        String yaml = String.join("\n", java.util.Arrays.copyOfRange(lines, 1, closing));
        JsonNode metadata;
        try {
            metadata = yamlMapper.readTree(yaml);
        } catch (JsonProcessingException exception) {
            throw new MarkdownCommandLoadException("front matter 无法解析: " + path, exception);
        }
        if (metadata == null || !metadata.isObject()) {
            throw new MarkdownCommandLoadException("front matter 必须是对象: " + path);
        }
        Iterator<String> fieldNames = metadata.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            if (!FRONT_MATTER_FIELDS.contains(field)) {
                throw new MarkdownCommandLoadException("front matter 字段未获准: " + field);
            }
        }
        String name = requiredText(metadata, "name", path);
        String description = requiredText(metadata, "description", path);
        String channel = requiredText(metadata, "channel", path);
        if (!CommandChannel.WORKFLOW_SKILL.name().equals(channel)) {
            throw new MarkdownCommandLoadException("Markdown 命令不允许通道: " + channel);
        }
        List<String> aliases = textList(metadata.get("aliases"), "aliases", path);
        List<CommandParameter> parameters = parameters(metadata.get("arguments"), path);
        CommandPermission permission = metadata.has("permission")
                ? parsePermission(metadata.get("permission"), path)
                : CommandPermission.OPERATOR;
        String body = String.join("\n", java.util.Arrays.copyOfRange(lines, closing + 1, lines.length)).strip();
        if (body.isBlank()) {
            throw new MarkdownCommandLoadException("命令正文不能为空: " + path);
        }
        try {
            renderer.validateTemplate(body, parameters);
        } catch (RuntimeException exception) {
            throw new MarkdownCommandLoadException("模板变量未获准: " + path, exception);
        }
        return new CommandDefinition(
                name, name, description, aliases, parameters,
                CommandChannel.WORKFLOW_SKILL, source, permission,
                new WorkflowCommandHandler(body, parameters, renderer, workflowBridge));
    }

    private String requiredText(JsonNode object, String field, Path path) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new MarkdownCommandLoadException("front matter 必填文本无效: " + field + "，文件: " + path);
        }
        return value.textValue();
    }

    private List<String> textList(JsonNode value, String field, Path path) {
        if (value == null) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new MarkdownCommandLoadException(field + " 必须是数组: " + path);
        }
        List<String> result = new ArrayList<>();
        value.forEach(item -> {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new MarkdownCommandLoadException(field + " 包含无效文本: " + path);
            }
            result.add(item.textValue());
        });
        return List.copyOf(result);
    }

    private List<CommandParameter> parameters(JsonNode value, Path path) {
        if (value == null) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new MarkdownCommandLoadException("arguments 必须是数组: " + path);
        }
        List<CommandParameter> result = new ArrayList<>();
        value.forEach(item -> {
            if (!item.isObject()) {
                throw new MarkdownCommandLoadException("arguments 必须是对象数组: " + path);
            }
            Set<String> allowed = Set.of("name", "description", "required");
            item.fieldNames().forEachRemaining(field -> {
                if (!allowed.contains(field)) {
                    throw new MarkdownCommandLoadException("arguments 字段未获准: " + field);
                }
            });
            String name = requiredText(item, "name", path);
            String description = item.has("description") ? requiredText(item, "description", path) : "";
            JsonNode required = item.get("required");
            if (required != null && !required.isBoolean()) {
                throw new MarkdownCommandLoadException("required 必须是布尔值: " + path);
            }
            result.add(new CommandParameter(name, description, required != null && required.booleanValue()));
        });
        return List.copyOf(result);
    }

    private CommandPermission parsePermission(JsonNode value, Path path) {
        if (value == null || !value.isTextual()) {
            throw new MarkdownCommandLoadException("permission 必须是文本: " + path);
        }
        try {
            return CommandPermission.valueOf(value.textValue());
        } catch (IllegalArgumentException exception) {
            throw new MarkdownCommandLoadException("permission 无效: " + value.textValue(), exception);
        }
    }

    private void validateSourceDuplicates(List<CommandDefinition> definitions) {
        Map<CommandSource, Set<String>> names = new java.util.EnumMap<>(CommandSource.class);
        for (CommandDefinition definition : definitions) {
            Set<String> sourceNames = names.computeIfAbsent(
                    definition.source(), ignored -> new HashSet<>());
            if (!sourceNames.add(definition.name())) {
                throw new MarkdownCommandLoadException(
                        "同一来源存在重复命令: " + definition.name());
            }
        }
    }
}
