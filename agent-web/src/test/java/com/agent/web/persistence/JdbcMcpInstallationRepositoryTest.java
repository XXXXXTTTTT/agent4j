package com.agent.web.persistence;

import com.agent.web.capability.InstallationScope;
import com.agent.web.mcp.catalog.OfficialMcpServerRecord;
import com.agent.web.mcp.installation.McpInstallationRecord;
import com.agent.web.mcp.installation.McpInstallationStatus;
import com.agent.web.mcp.installation.McpSourceSnapshot;
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
                List.of(),
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
    }

    private DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(POSTGRES.getDriverClassName());
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}
