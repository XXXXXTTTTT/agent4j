package com.agent.web.persistence;

import com.agent.web.capability.CapabilityManagementAuditEvent;
import com.agent.web.capability.InstallationScope;
import com.agent.web.skill.InstalledSkillRecord;
import com.agent.web.skill.SkillInstallationRecord;
import com.agent.web.skill.SkillInstallationStatus;
import com.agent.web.skill.SkillSnapshotRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcSkillInstallationRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");
    private static final UUID WORKSPACE_ID = UUID.fromString("8de5cf09-6ab9-46df-aa80-0adc5c66dc24");
    private static final UUID OTHER_WORKSPACE_ID = UUID.fromString("9de5cf09-6ab9-46df-aa80-0adc5c66dc24");
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcClient jdbc;
    private JdbcSkillInstallationRepository repository;

    @BeforeAll
    static void startPostgres() {
        boolean dockerAvailable;
        try {
            dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException exception) {
            Assumptions.assumeTrue(false, "Docker Engine 不可用: " + exception.getMessage());
            return;
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker Engine 不可用");
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @BeforeEach
    void setUp() {
        DataSource dataSource = dataSource();
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("truncate table agent_skill_installations, agent_skill_snapshots, "
                + "agent_capability_management_audit, agent_workspace_members, agent_workspaces, "
                + "agent_users cascade").update();
        saveUser("skill-test-user", "Skill Test User");
        saveUser("other-skill-test-user", "Other Skill Test User");
        saveWorkspace(WORKSPACE_ID, "skill-test-user", "Skill Test Workspace", "D:/agent4j");
        saveWorkspace(OTHER_WORKSPACE_ID, "skill-test-user", "Other Skill Test Workspace", "D:/agent4j-other");
        repository = new JdbcSkillInstallationRepository(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void findsOnlyApprovedSkillsVisibleToActorAndWorkspaceInAscendingUpdateOrder() {
        SkillInstallationRecord workspaceFirst = saveInstallation(
                "skill-test-user", InstallationScope.WORKSPACE, WORKSPACE_ID,
                Instant.parse("2026-08-12T00:00:01Z"), "workspace-first",
                UUID.fromString("10000000-0000-0000-0000-000000000001"));
        SkillInstallationRecord globalSecondById = saveInstallation(
                "skill-test-user", InstallationScope.USER_GLOBAL, null,
                Instant.parse("2026-08-12T00:00:02Z"), "global-second-by-id",
                UUID.fromString("10000000-0000-0000-0000-000000000002"));
        SkillInstallationRecord globalThirdById = saveInstallation(
                "skill-test-user", InstallationScope.USER_GLOBAL, null,
                Instant.parse("2026-08-12T00:00:02Z"), "global-third-by-id",
                UUID.fromString("10000000-0000-0000-0000-000000000003"));
        saveInstallation("skill-test-user", InstallationScope.WORKSPACE, OTHER_WORKSPACE_ID,
                Instant.parse("2026-08-12T00:00:03Z"), "other-workspace");
        saveInstallation("other-skill-test-user", InstallationScope.USER_GLOBAL, null,
                Instant.parse("2026-08-12T00:00:04Z"), "other-actor");
        saveInstallation("skill-test-user", InstallationScope.USER_GLOBAL, null,
                SkillInstallationStatus.PENDING_APPROVAL, Instant.parse("2026-08-12T00:00:05Z"), "pending");

        List<InstalledSkillRecord> installedSkills = repository.findInstalledSkills("skill-test-user", WORKSPACE_ID);

        assertThat(installedSkills)
                .extracting(record -> record.installation().skillInstallationId())
                .containsExactly(workspaceFirst.skillInstallationId(), globalSecondById.skillInstallationId(),
                        globalThirdById.skillInstallationId());
        assertThat(installedSkills)
                .extracting(record -> record.installation().scope())
                .containsExactly(InstallationScope.WORKSPACE, InstallationScope.USER_GLOBAL,
                        InstallationScope.USER_GLOBAL);
        assertThat(repository.installationsUpdatedAt("skill-test-user", WORKSPACE_ID))
                .isEqualTo(Instant.parse("2026-08-12T00:00:02Z"));
    }

    @Test
    void returnsEpochWhenActorHasNoApprovedSkillsVisibleToWorkspace() {
        saveInstallation("skill-test-user", InstallationScope.WORKSPACE, OTHER_WORKSPACE_ID,
                Instant.parse("2026-08-12T00:00:01Z"), "other-workspace");
        saveInstallation("other-skill-test-user", InstallationScope.USER_GLOBAL, null,
                Instant.parse("2026-08-12T00:00:02Z"), "other-actor");
        saveInstallation("skill-test-user", InstallationScope.USER_GLOBAL, null,
                SkillInstallationStatus.REJECTED, Instant.parse("2026-08-12T00:00:03Z"), "rejected");

        assertThat(repository.findInstalledSkills("skill-test-user", WORKSPACE_ID)).isEmpty();
        assertThat(repository.installationsUpdatedAt("skill-test-user", WORKSPACE_ID)).isEqualTo(Instant.EPOCH);
    }

    @Test
    void rollsBackSnapshotInstallationAndAuditWhenAuditInsertFails() {
        SkillSnapshotRecord snapshot = snapshot("rollback");
        SkillInstallationRecord installation = new SkillInstallationRecord(
                UUID.randomUUID(), snapshot.skillSnapshotId(), InstallationScope.WORKSPACE, WORKSPACE_ID,
                "skill-test-user", SkillInstallationStatus.APPROVED, "a".repeat(64), NOW, NOW, NOW, 0);
        CapabilityManagementAuditEvent invalidAudit = new CapabilityManagementAuditEvent(
                "SKILL_INSTALLATION_CONFIRMED", "missing-skill-test-user", WORKSPACE_ID, null,
                installation.skillInstallationId(), null, snapshot.commitSha(), "SUCCESS", NOW);

        assertThatThrownBy(() -> repository.confirmSkill(snapshot, installation, invalidAudit))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(count("agent_skill_snapshots")).isZero();
        assertThat(count("agent_skill_installations")).isZero();
        assertThat(count("agent_capability_management_audit")).isZero();
    }

    @Test
    void permitsOnlyOneRemovalForTheSameExpectedVersion() {
        SkillInstallationRecord installation = saveInstallation(
                "skill-test-user", InstallationScope.WORKSPACE, WORKSPACE_ID, NOW, "remove-once");
        CapabilityManagementAuditEvent audit = new CapabilityManagementAuditEvent(
                "SKILL_INSTALLATION_REMOVED", "skill-test-user", WORKSPACE_ID, null,
                installation.skillInstallationId(), null, "", "SUCCESS", NOW);

        SkillInstallationRecord removed = repository.removeInstallation(
                installation.skillInstallationId(), "skill-test-user", WORKSPACE_ID, 0, audit);

        assertThat(removed.status()).isEqualTo(SkillInstallationStatus.REMOVED);
        assertThat(removed.version()).isEqualTo(1);
        assertThatThrownBy(() -> repository.removeInstallation(
                installation.skillInstallationId(), "skill-test-user", WORKSPACE_ID, 0, audit))
                .isInstanceOf(com.agent.web.skill.SkillInstallationConflictException.class);
        assertThat(repository.findInstallations("skill-test-user", WORKSPACE_ID))
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.status()).isEqualTo(SkillInstallationStatus.REMOVED);
                    assertThat(saved.version()).isEqualTo(1);
                });
    }

    private SkillInstallationRecord saveInstallation(
            String actorUserId, InstallationScope scope, UUID workspaceId, Instant updatedAt, String suffix) {
        return saveInstallation(actorUserId, scope, workspaceId, SkillInstallationStatus.APPROVED, updatedAt, suffix);
    }

    private SkillInstallationRecord saveInstallation(
            String actorUserId, InstallationScope scope, UUID workspaceId, Instant updatedAt, String suffix,
            UUID skillInstallationId) {
        return saveInstallation(actorUserId, scope, workspaceId, SkillInstallationStatus.APPROVED, updatedAt,
                suffix, skillInstallationId);
    }

    private SkillInstallationRecord saveInstallation(
            String actorUserId, InstallationScope scope, UUID workspaceId, SkillInstallationStatus status,
            Instant updatedAt, String suffix) {
        return saveInstallation(actorUserId, scope, workspaceId, status, updatedAt, suffix, UUID.randomUUID());
    }

    private SkillInstallationRecord saveInstallation(
            String actorUserId, InstallationScope scope, UUID workspaceId, SkillInstallationStatus status,
            Instant updatedAt, String suffix, UUID skillInstallationId) {
        SkillSnapshotRecord snapshot = snapshot(suffix, updatedAt);
        SkillInstallationRecord installation = new SkillInstallationRecord(
                skillInstallationId, snapshot.skillSnapshotId(), scope, workspaceId, actorUserId, status,
                "a".repeat(64), updatedAt, updatedAt, updatedAt, 0);
        return repository.confirmSkill(snapshot, installation, new CapabilityManagementAuditEvent(
                "SKILL_INSTALLATION_CONFIRMED", actorUserId, workspaceId, installation.skillInstallationId(), null,
                null, snapshot.commitSha(), "SUCCESS", updatedAt));
    }

    private SkillSnapshotRecord snapshot(String suffix) {
        return snapshot(suffix, NOW);
    }

    private SkillSnapshotRecord snapshot(String suffix, Instant updatedAt) {
        return new SkillSnapshotRecord(
                UUID.randomUUID(), URI.create("https://github.com/agent4j/" + suffix), "agent4j/" + suffix,
                "0123456789012345678901234567890123456789", "blob-" + suffix, "SKILL.md", "MIT",
                String.format("%064d", Math.abs(suffix.hashCode())), "Skill " + suffix,
                List.of("code.patch"), "---\nname: " + suffix + "\n---\n", updatedAt);
    }

    private void saveUser(String userId, String displayName) {
        jdbc.sql("insert into agent_users (user_id, display_name, enabled, created_at, updated_at) "
                        + "values (:userId, :displayName, true, :now, :now)")
                .param("userId", userId)
                .param("displayName", displayName)
                .param("now", java.sql.Timestamp.from(NOW))
                .update();
    }

    private void saveWorkspace(UUID workspaceId, String ownerUserId, String displayName, String workspacePath) {
        jdbc.sql("insert into agent_workspaces (workspace_id, owner_user_id, display_name, workspace_path, repository_id, created_at, updated_at) "
                        + "values (:workspaceId, :ownerUserId, :displayName, :workspacePath, :repositoryId, :now, :now)")
                .param("workspaceId", workspaceId)
                .param("ownerUserId", ownerUserId)
                .param("displayName", displayName)
                .param("workspacePath", workspacePath)
                .param("repositoryId", "repository-" + workspaceId)
                .param("now", java.sql.Timestamp.from(NOW))
                .update();
    }

    private DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(POSTGRES.getDriverClassName());
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    private int count(String table) {
        return jdbc.sql("select count(*) from " + table).query(Integer.class).single();
    }
}
