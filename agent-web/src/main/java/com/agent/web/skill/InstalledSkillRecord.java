package com.agent.web.skill;

/** 同一查询范围内的已批准 Skill 安装及其不可变正文快照。 */
public record InstalledSkillRecord(
        SkillInstallationRecord installation,
        SkillSnapshotRecord snapshot) {
}
