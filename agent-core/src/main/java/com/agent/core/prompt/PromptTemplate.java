package com.agent.core.prompt;

import java.util.Objects;
import java.util.Set;

/** 具有精确名称、版本和静态/动态分区的 Prompt 模板。 */
public record PromptTemplate(
        String name,
        String version,
        String staticSection,
        String dynamicTemplate,
        Set<String> requiredVariables) {

    /** 校验并冻结模板定义。 */
    public PromptTemplate {
        name = requireText(name, "name");
        version = requireText(version, "version");
        staticSection = requireText(staticSection, "staticSection");
        dynamicTemplate = requireText(dynamicTemplate, "dynamicTemplate");
        requiredVariables = Set.copyOf(Objects.requireNonNull(
                requiredVariables, "requiredVariables 不能为空"));
        for (String variable : requiredVariables) {
            String exactVariable = requireText(variable, "requiredVariables 元素");
            if (!dynamicTemplate.contains("{{" + exactVariable + "}}")) {
                throw new IllegalArgumentException(
                        "dynamicTemplate 缺少必需变量: " + exactVariable);
            }
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }
}
