package com.agent.web.skill;

import java.util.UUID;

/** Skill 安装的版本或生命周期状态与当前持久化记录不一致。 */
public final class SkillInstallationConflictException extends RuntimeException {
    public SkillInstallationConflictException(UUID skillInstallationId, long expectedVersion) {
        super("Skill 安装版本或状态冲突: " + skillInstallationId + ", expectedVersion=" + expectedVersion);
    }
}
