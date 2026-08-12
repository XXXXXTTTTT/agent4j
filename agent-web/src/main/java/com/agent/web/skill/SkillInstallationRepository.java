package com.agent.web.skill;

import com.agent.web.capability.InstallationScope;

import java.util.List;
import java.util.UUID;

/** Skill 快照与安装记录的持久化端口。 */
public interface SkillInstallationRepository {
    default SkillInstallationRecord confirmSkill(
            SkillSnapshotRecord snapshot, SkillInstallationRecord installation) {
        saveSnapshot(snapshot);
        return saveInstallation(installation);
    }
    SkillSnapshotRecord saveSnapshot(SkillSnapshotRecord snapshot);
    SkillInstallationRecord saveInstallation(SkillInstallationRecord installation);
    List<SkillInstallationRecord> findInstallations(String actorUserId, UUID workspaceId);
    boolean deleteInstallation(UUID skillInstallationId, String actorUserId, UUID workspaceId);

    default SkillInstallationRecord transition(
            UUID skillInstallationId, long expectedVersion, SkillInstallationStatus from,
            SkillInstallationStatus to) {
        throw new UnsupportedOperationException("Skill 状态迁移未实现");
    }
}
