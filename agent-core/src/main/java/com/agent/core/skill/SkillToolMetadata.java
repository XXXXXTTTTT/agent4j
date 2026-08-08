package com.agent.core.skill;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/** Skill 激活后可公开的只读工具元数据。 */
public record SkillToolMetadata(String name, String description, JsonNode inputSchema) {

    public SkillToolMetadata {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description 不能为空");
        }
        inputSchema = Objects.requireNonNull(inputSchema, "inputSchema 不能为空").deepCopy();
        if (!inputSchema.isObject()) {
            throw new IllegalArgumentException("inputSchema 必须是 JSON object");
        }
    }

    @Override
    public JsonNode inputSchema() {
        return inputSchema.deepCopy();
    }
}
