package com.agent.web.skill;

import com.agent.web.capability.InstallationScope;

import java.util.List;
import java.util.UUID;

/** Skill 快照与安装记录的持久化端口。 */
public interface SkillInstallationRepository {
    SkillInstallationRecord confirmSkill(SkillSnapshotRecord snapshot, SkillInstallationRecord installation,
                                         com.agent.web.capability.CapabilityManagementAuditEvent auditEvent);
    List<SkillInstallationRecord> findInstallations(String actorUserId, UUID workspaceId);
    SkillInstallationRecord removeInstallation(UUID skillInstallationId, String actorUserId, UUID workspaceId,
                                               long expectedVersion,
                                               com.agent.web.capability.CapabilityManagementAuditEvent auditEvent);

    SkillInstallationRecord transition(
            UUID skillInstallationId, long expectedVersion, SkillInstallationStatus from,
            SkillInstallationStatus to);
}
