package com.agent.web.command;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/** 从版本管理的资源文件读取内置工作流模板。 */
final class BuiltInWorkflowTemplates {

    private static final String RESOURCE_PATH = "/commands/built-in-workflow-templates.properties";

    private final Map<String, String> templates;

    BuiltInWorkflowTemplates() {
        this.templates = load();
    }

    /** 返回指定命令的精确模板。 */
    String template(String name) {
        String exactName = Objects.requireNonNull(name, "name 不能为空");
        String value = templates.get(exactName);
        if (value == null) {
            throw new IllegalArgumentException("未定义内置工作流模板: " + exactName);
        }
        return value;
    }

    private Map<String, String> load() {
        try (InputStream stream = BuiltInWorkflowTemplates.class.getResourceAsStream(RESOURCE_PATH)) {
            if (stream == null) {
                throw new IllegalStateException("内置工作流模板资源不存在: " + RESOURCE_PATH);
            }
            Properties properties = new Properties();
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            Map<String, String> loaded = new LinkedHashMap<>();
            for (String name : properties.stringPropertyNames()) {
                String value = properties.getProperty(name);
                if (name.isBlank() || value == null || value.isBlank()) {
                    throw new IllegalStateException("内置工作流模板无效: " + name);
                }
                loaded.put(name, value);
            }
            return Map.copyOf(loaded);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取内置工作流模板资源", exception);
        }
    }
}
