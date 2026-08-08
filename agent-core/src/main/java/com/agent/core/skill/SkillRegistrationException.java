package com.agent.core.skill;

/** Skill 目录构造失败。 */
public final class SkillRegistrationException extends RuntimeException {

    private final String skillName;

    public SkillRegistrationException(String skillName, String message, Throwable cause) {
        super(message, cause);
        this.skillName = skillName == null ? "" : skillName;
    }

    public String skillName() {
        return skillName;
    }
}
