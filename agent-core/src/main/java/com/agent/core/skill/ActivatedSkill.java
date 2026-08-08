package com.agent.core.skill;

import java.util.List;
import java.util.Objects;

/** 本次请求已完整披露的 Skill。 */
public record ActivatedSkill(
        String name,
        String version,
        List<SkillToolMetadata> tools,
        String promptFragment) {

    public ActivatedSkill {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version 不能为空");
        }
        tools = List.copyOf(Objects.requireNonNull(tools, "tools 不能为空"));
        if (tools.isEmpty()) {
            throw new IllegalArgumentException("tools 不能为空列表");
        }
        if (promptFragment == null || promptFragment.isBlank()) {
            throw new IllegalArgumentException("promptFragment 不能为空");
        }
    }
}
