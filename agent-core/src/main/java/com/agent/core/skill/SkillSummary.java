package com.agent.core.skill;

/** 默认发现阶段可公开的 Skill 摘要。 */
public record SkillSummary(String name, String version, String description) {

    public SkillSummary {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version 不能为空");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description 不能为空");
        }
    }
}
