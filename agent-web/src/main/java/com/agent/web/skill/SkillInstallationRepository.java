package com.agent.web.skill;

import com.agent.web.capability.InstallationScope;

import java.util.List;
import java.util.UUID;
import java.time.Instant;

/** Skill 快照与安装记录的持久化端口。 */
public interface SkillInstallationRepository {
    SkillInstallationRecord confirmSkill(SkillSnapshotRecord snapshot, SkillInstallationRecord installation,
                                         com.agent.web.capability.CapabilityManagementAuditEvent auditEvent);
    List<SkillInstallationRecord> findInstallations(String actorUserId, UUID workspaceId);

    /** 查询当前主体与工作区可用的安装和快照。 */
    default List<InstalledSkillRecord> findInstalledSkills(String actorUserId, UUID workspaceId) {
        return List.of();
    }

    /** 返回同一查询范围内安装记录的最大更新时间。 */
    default Instant installationsUpdatedAt(String actorUserId, UUID workspaceId) {
        return Instant.EPOCH;
    }
    SkillInstallationRecord removeInstallation(UUID skillInstallationId, String actorUserId, UUID workspaceId,
                                               long expectedVersion,
                                               com.agent.web.capability.CapabilityManagementAuditEvent auditEvent);

    SkillInstallationRecord transition(
            UUID skillInstallationId, long expectedVersion, SkillInstallationStatus from,
            SkillInstallationStatus to);
}
