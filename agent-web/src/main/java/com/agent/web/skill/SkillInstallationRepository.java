package com.agent.web.skill;

import com.agent.web.capability.InstallationScope;

import java.util.List;
import java.util.UUID;

/** Skill 快照与安装记录的持久化端口。 */
public interface SkillInstallationRepository {
    SkillSnapshotRecord saveSnapshot(SkillSnapshotRecord snapshot);
    SkillInstallationRecord saveInstallation(SkillInstallationRecord installation);
    List<SkillInstallationRecord> findInstallations(String actorUserId, UUID workspaceId, InstallationScope scope);
}
