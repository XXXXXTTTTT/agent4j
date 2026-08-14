package com.agent.web.persistence;

import com.agent.web.capability.InstallationScope;
import com.agent.web.mcp.catalog.OfficialMcpServerRecord;
import com.agent.web.mcp.installation.McpInstallationRecord;
import com.agent.web.mcp.installation.McpInstallationStatus;
import com.agent.web.mcp.installation.McpSourceSnapshot;
import com.agent.web.mcp.installation.McpNetworkMode;
import com.agent.web.mcp.installation.McpRuntimeStartCompletion;
import com.agent.web.mcp.installation.McpToolBindingRecord;
import com.agent.web.mcp.installation.WorkspaceMountMode;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcMcpInstallationRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");
    private static final UUID WORKSPACE_ID =
            UUID.fromString("6c82db74-2d5a-4d2e-8e0d-4f51f4b399a1");
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcClient jdbc;
    private JdbcMcpInstallationRepository repository;

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
        jdbc.sql("truncate table agent_mcp_installations, agent_mcp_installation_snapshots, "
                + "agent_capability_management_audit, "
                + "agent_workspace_members, agent_workspaces, agent_users cascade").update();
        jdbc.sql("insert into agent_users (user_id, display_name, enabled, created_at, updated_at) "
                + "values (:userId, :displayName, true, :now, :now)")
                .param("userId", "mcp-test-user")
                .param("displayName", "MCP Test User")
                .param("now", java.sql.Timestamp.from(NOW))
                .update();
        jdbc.sql("insert into agent_workspaces (workspace_id, owner_user_id, display_name, workspace_path, repository_id, created_at, updated_at) "
                + "values (:workspaceId, :ownerUserId, :displayName, :workspacePath, :repositoryId, :now, :now)")
                .param("workspaceId", WORKSPACE_ID)
                .param("ownerUserId", "mcp-test-user")
                .param("displayName", "MCP Test Workspace")
                .param("workspacePath", "D:/agent4j")
                .param("repositoryId", "mcp-test-repository")
                .param("now", java.sql.Timestamp.from(NOW))
                .update();
        repository = new JdbcMcpInstallationRepository(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void persistsOfficialSnapshotAndWorkspaceInstallation() {
        OfficialMcpServerRecord server = new OfficialMcpServerRecord(
                "filesystem",
                "src/filesystem",
                URI.create("https://raw.githubusercontent.com/modelcontextprotocol/servers/main/src/filesystem"),
                "0123456789012345678901234567890123456789",
                Map.of("package.json", "abcdef0123456789"),
                "0123456789012345678901234567890123456789012345678901234567890123",
                "1.0.0",
                "Filesystem MCP server",
                "MIT",
                "npx",
                List.of("-y", "@modelcontextprotocol/server-filesystem"),
                "node",
                List.of("MCP_TOKEN"),
                "Official filesystem server");
        McpSourceSnapshot snapshot = McpSourceSnapshot.from(UUID.randomUUID(), server, NOW);

        McpInstallationRecord installation = new McpInstallationRecord(
                UUID.randomUUID(), snapshot.snapshotId(), InstallationScope.WORKSPACE,
                WORKSPACE_ID, "mcp-test-user", McpInstallationStatus.STOPPED,
                "a".repeat(64), NOW, NOW, NOW);
        assertThat(repository.confirmInstallation(new com.agent.web.mcp.installation.McpInstallationCommand(
                snapshot, installation, new com.agent.web.capability.CapabilityManagementAuditEvent(
                        "MCP_INSTALLATION_CONFIRMED", "mcp-test-user", WORKSPACE_ID,
                        installation.installationId(), null, null, snapshot.commitSha(), "SUCCESS", NOW)))).isEqualTo(installation);
        assertThat(repository.findInstallations("mcp-test-user", WORKSPACE_ID))
                .containsExactly(installation);
        assertThat(repository.findInstallationDetails("mcp-test-user", WORKSPACE_ID))
                .singleElement()
                .satisfies(details -> {
                    assertThat(details.installation()).isEqualTo(installation);
                    assertThat(details.environmentVariableNames()).containsExactly("MCP_TOKEN");
                });
    }

    @Test
    void rollsBackSnapshotInstallationAndAuditWhenAuditInsertFails() {
        McpSourceSnapshot snapshot = snapshot();
        McpInstallationRecord installation = installation(snapshot, McpInstallationStatus.STOPPED, 0);
        com.agent.web.capability.CapabilityManagementAuditEvent invalidAudit =
                new com.agent.web.capability.CapabilityManagementAuditEvent(
                        "MCP_INSTALLATION_CONFIRMED", "missing-mcp-test-user", WORKSPACE_ID,
                        installation.installationId(), null, null, snapshot.commitSha(), "SUCCESS", NOW);

        assertThatThrownBy(() -> repository.confirmInstallation(
                new com.agent.web.mcp.installation.McpInstallationCommand(snapshot, installation, invalidAudit)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(count("agent_mcp_installation_snapshots")).isZero();
        assertThat(count("agent_mcp_installations")).isZero();
        assertThat(count("agent_capability_management_audit")).isZero();
    }

    @Test
    void permitsOnlyOneStatusTransitionForTheSameExpectedVersion() {
        McpSourceSnapshot snapshot = snapshot();
        McpInstallationRecord installation = installation(snapshot, McpInstallationStatus.STOPPED, 0);
        repository.confirmInstallation(new com.agent.web.mcp.installation.McpInstallationCommand(snapshot, installation,
                audit(installation, snapshot, "MCP_INSTALLATION_CONFIRMED", "STOPPED", "STOPPED")));

        McpInstallationRecord first = repository.beginStart(installation.installationId(), "mcp-test-user", WORKSPACE_ID,
                WORKSPACE_ID, 0, audit(installation, snapshot, "MCP_INSTALLATION_STARTING", "STOPPED", "INSTALLING"));

        assertThat(first.status()).isEqualTo(McpInstallationStatus.INSTALLING);
        assertThat(first.version()).isEqualTo(1);
        assertThatThrownBy(() -> repository.beginStart(installation.installationId(), "mcp-test-user", WORKSPACE_ID,
                WORKSPACE_ID, 0, audit(installation, snapshot, "MCP_INSTALLATION_STARTING", "STOPPED", "INSTALLING")))
                .isInstanceOf(com.agent.web.mcp.installation.McpInstallationConflictException.class);
        assertThat(repository.findInstallations("mcp-test-user", WORKSPACE_ID))
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.status()).isEqualTo(McpInstallationStatus.INSTALLING);
                    assertThat(saved.version()).isEqualTo(1);
                });
    }

    @Test
    void completesStartAndStopAsSingleVersionedAggregate() {
        McpSourceSnapshot snapshot = snapshot();
        McpInstallationRecord installation = installation(snapshot, McpInstallationStatus.STOPPED, 0);
        repository.confirmInstallation(new com.agent.web.mcp.installation.McpInstallationCommand(snapshot, installation,
                audit(installation, snapshot, "MCP_INSTALLATION_CONFIRMED", "STOPPED", "STOPPED")));

        McpInstallationRecord installing = repository.beginStart(installation.installationId(), "mcp-test-user", WORKSPACE_ID,
                WORKSPACE_ID, 0, audit(installation, snapshot, "MCP_INSTALLATION_STARTING", "STOPPED", "INSTALLING"));
        assertThat(installing.status()).isEqualTo(McpInstallationStatus.INSTALLING);
        assertThat(installing.version()).isEqualTo(1);
        assertThat(installing.runtimeWorkspaceId()).isEqualTo(WORKSPACE_ID);
        var binding = new com.agent.web.mcp.installation.McpToolBindingRecord(installation.installationId(),
                "mcp.9169db66a55544dda1e92ff4b1633ea6.echo", "echo", com.agent.core.tool.ToolRiskLevel.HIGH,
                java.util.Set.of(com.agent.core.intent.RequiredCapability.TOOL), NOW);
        McpInstallationRecord running = repository.completeStart(new com.agent.web.mcp.installation.McpRuntimeStartCompletion(
                installation.installationId(), 1, WORKSPACE_ID, "container-1", List.of(binding),
                audit(installation, snapshot, "MCP_INSTALLATION_STARTED", "INSTALLING", "RUNNING")));
        assertThat(running.status()).isEqualTo(McpInstallationStatus.RUNNING);
        assertThat(running.runtimeWorkspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(repository.findInstallation(installation.installationId(), "mcp-test-user", WORKSPACE_ID))
                .get().extracting(value -> value.bindings()).asList().hasSize(1);

        McpInstallationRecord stopping = repository.beginStop(installation.installationId(), "mcp-test-user", WORKSPACE_ID,
                2, audit(installation, snapshot, "MCP_INSTALLATION_STOPPING", "RUNNING", "STOPPING"));
        McpInstallationRecord stopped = repository.completeStop(new com.agent.web.mcp.installation.McpRuntimeStopCompletion(
                installation.installationId(), stopping.version(),
                audit(installation, snapshot, "MCP_INSTALLATION_STOPPED", "STOPPING", "STOPPED")));
        assertThat(stopped.status()).isEqualTo(McpInstallationStatus.STOPPED);
        assertThat(stopped.runtimeWorkspaceId()).isNull();
        assertThat(stopped.containerId()).isNull();
        assertThat(repository.findInstallation(installation.installationId(), "mcp-test-user", WORKSPACE_ID)
                .orElseThrow().bindings()).isEmpty();
    }

    @Test
    void findsOnlyCurrentActorWorkspaceAndGlobalRunningInstallations() {
        McpSourceSnapshot workspaceSnapshot = snapshot();
        McpInstallationRecord workspaceInstallation = installation(workspaceSnapshot, McpInstallationStatus.STOPPED, 0);
        repository.confirmInstallation(new com.agent.web.mcp.installation.McpInstallationCommand(workspaceSnapshot,
                workspaceInstallation, audit(workspaceInstallation, workspaceSnapshot,
                "MCP_INSTALLATION_CONFIRMED", "STOPPED", "STOPPED")));
        start(workspaceInstallation, workspaceSnapshot, "mcp.workspace.echo");

        McpSourceSnapshot globalSnapshot = snapshot();
        McpInstallationRecord globalInstallation = new McpInstallationRecord(UUID.randomUUID(), globalSnapshot.snapshotId(),
                InstallationScope.USER_GLOBAL, null, "mcp-test-user", McpInstallationStatus.STOPPED,
                "b".repeat(64), NOW, NOW, NOW, com.agent.core.tool.ToolRiskLevel.HIGH,
                java.util.Set.of(com.agent.core.intent.RequiredCapability.TOOL), WorkspaceMountMode.NONE,
                McpNetworkMode.NONE, "node:22-alpine", true, null, null, null, 0);
        repository.confirmInstallation(new com.agent.web.mcp.installation.McpInstallationCommand(globalSnapshot,
                globalInstallation, audit(globalInstallation, globalSnapshot,
                "MCP_INSTALLATION_CONFIRMED", "STOPPED", "STOPPED")));
        start(globalInstallation, globalSnapshot, "mcp.global.echo");

        McpSourceSnapshot stoppedSnapshot = snapshot();
        McpInstallationRecord stopped = installation(stoppedSnapshot, McpInstallationStatus.STOPPED, 0);
        repository.confirmInstallation(new com.agent.web.mcp.installation.McpInstallationCommand(stoppedSnapshot, stopped,
                audit(stopped, stoppedSnapshot, "MCP_INSTALLATION_CONFIRMED", "STOPPED", "STOPPED")));

        assertThat(repository.findRunningInstallations("mcp-test-user", WORKSPACE_ID))
                .extracting(aggregate -> aggregate.installation().installationId())
                .containsExactlyInAnyOrder(workspaceInstallation.installationId(), globalInstallation.installationId());
        assertThat(repository.findRunningInstallations("other-user", WORKSPACE_ID)).isEmpty();
        UUID otherWorkspace = UUID.fromString("d4289a7e-c87f-46b3-83f5-f893bcba166d");
        assertThat(repository.findRunningInstallations("mcp-test-user", otherWorkspace))
                .isEmpty();
    }

    @Test
    void persistsPreparedMaterialAndRejectsStaleInstallationVersion() throws Exception {
        McpSourceSnapshot snapshot = snapshot();
        McpInstallationRecord installation = installation(snapshot, McpInstallationStatus.STOPPED, 0);
        repository.confirmInstallation(new com.agent.web.mcp.installation.McpInstallationCommand(snapshot, installation,
                audit(installation, snapshot, "MCP_INSTALLATION_CONFIRMED", "STOPPED", "STOPPED")));
        java.nio.file.Path materialDirectory = java.nio.file.Files.createTempDirectory("mcp-prepared-material");
        java.nio.file.Files.writeString(materialDirectory.resolve("server.mjs"), "process.stdout.write('ready');");
        com.agent.web.mcp.installation.McpPreparedMaterialRecord material = new com.agent.web.mcp.installation.McpPreparedMaterialRecord(
                materialDirectory, "d".repeat(64), "server.mjs", List.of(), NOW);

        McpInstallationRecord prepared = repository.completeMaterialPreparation(installation.installationId(), "mcp-test-user",
                WORKSPACE_ID, 0, material, audit(installation, snapshot, "MCP_MATERIAL_PREPARED", "STOPPED", "STOPPED"));

        assertThat(prepared.version()).isEqualTo(1);
        assertThat(repository.findPreparedMaterial(snapshot.snapshotId())).contains(material);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.completeMaterialPreparation(
                installation.installationId(), "mcp-test-user", WORKSPACE_ID, 0, material,
                audit(installation, snapshot, "MCP_MATERIAL_PREPARED", "STOPPED", "STOPPED")))
                .isInstanceOf(com.agent.web.mcp.installation.McpInstallationConflictException.class);
    }

    private McpSourceSnapshot snapshot() {
        OfficialMcpServerRecord server = new OfficialMcpServerRecord(
                "filesystem", "src/filesystem", URI.create("https://raw.githubusercontent.com/modelcontextprotocol/servers/main/src/filesystem"),
                "0123456789012345678901234567890123456789", Map.of("package.json", "abcdef0123456789"),
                "0123456789012345678901234567890123456789012345678901234567890123", "1.0.0", "Filesystem MCP server",
                "MIT", "npx", List.of("-y", "@modelcontextprotocol/server-filesystem"), "node", List.of(), "Official filesystem server");
        return McpSourceSnapshot.from(UUID.randomUUID(), server, NOW);
    }

    private McpInstallationRecord installation(McpSourceSnapshot snapshot, McpInstallationStatus status, long version) {
        return new McpInstallationRecord(UUID.randomUUID(), snapshot.snapshotId(), InstallationScope.WORKSPACE,
                WORKSPACE_ID, "mcp-test-user", status, "a".repeat(64), NOW, NOW, NOW,
                com.agent.core.tool.ToolRiskLevel.HIGH, java.util.Set.of(com.agent.core.intent.RequiredCapability.TOOL),
                com.agent.web.mcp.installation.WorkspaceMountMode.NONE, com.agent.web.mcp.installation.McpNetworkMode.NONE,
                "node:22-alpine", true, null, null, null, version);
    }

    private void start(McpInstallationRecord installation, McpSourceSnapshot snapshot, String localToolName) {
        McpInstallationRecord installing = repository.beginStart(installation.installationId(), "mcp-test-user", WORKSPACE_ID,
                WORKSPACE_ID, 0, audit(installation, snapshot, "MCP_INSTALLATION_STARTING", "STOPPED", "INSTALLING"));
        McpToolBindingRecord binding = new McpToolBindingRecord(installation.installationId(), localToolName, "echo",
                com.agent.core.tool.ToolRiskLevel.HIGH, java.util.Set.of(com.agent.core.intent.RequiredCapability.TOOL), NOW);
        repository.completeStart(new McpRuntimeStartCompletion(installation.installationId(), installing.version(), WORKSPACE_ID,
                "container-" + installation.installationId(), List.of(binding),
                audit(installation, snapshot, "MCP_INSTALLATION_STARTED", "INSTALLING", "RUNNING")));
    }

    private com.agent.web.capability.CapabilityManagementAuditEvent audit(
            McpInstallationRecord installation, McpSourceSnapshot snapshot, String type, String from, String to) {
        return new com.agent.web.capability.CapabilityManagementAuditEvent(type, "mcp-test-user", WORKSPACE_ID,
                installation.installationId(), null, null, snapshot.commitSha(), "SUCCESS", NOW,
                UUID.randomUUID(), from, to, "");
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
