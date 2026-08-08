package com.agent.core.skill;

import java.util.List;
import java.util.Objects;

/** 具有渐进披露分区和审计指纹的 Skill Prompt 上下文。 */
public record SkillPromptContext(
        String discoverySection,
        String activationSection,
        List<SkillSummary> availableSkills,
        List<ActivatedSkill> activatedSkills,
        String fingerprint) {

    public SkillPromptContext {
        Objects.requireNonNull(discoverySection, "discoverySection 不能为空");
        Objects.requireNonNull(activationSection, "activationSection 不能为空");
        availableSkills = List.copyOf(Objects.requireNonNull(availableSkills, "availableSkills 不能为空"));
        activatedSkills = List.copyOf(Objects.requireNonNull(activatedSkills, "activatedSkills 不能为空"));
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("fingerprint 必须是 64 位小写 SHA-256");
        }
    }
}
