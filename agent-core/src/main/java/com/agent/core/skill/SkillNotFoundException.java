package com.agent.core.skill;

/** 显式请求的 Skill 不存在。 */
public final class SkillNotFoundException extends RuntimeException {

    private final String skillName;

    public SkillNotFoundException(String skillName) {
        super("Skill 未注册: " + skillName);
        this.skillName = skillName;
    }

    public String skillName() {
        return skillName;
    }
}
