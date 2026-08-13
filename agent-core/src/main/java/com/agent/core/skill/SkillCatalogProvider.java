package com.agent.core.skill;

import java.util.UUID;

/** 按可信 Run 身份解析已冻结或即将冻结的 Skill 目录。 */
@FunctionalInterface
public interface SkillCatalogProvider {
    SkillCatalogSnapshot resolve(String actorUserId, UUID workspaceId);
}
