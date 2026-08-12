package com.agent.web.mcp.runtime;

import com.agent.core.intent.RequiredCapability;
import com.agent.core.tool.DefaultToolRegistry;
import com.agent.core.tool.ToolCall;
import com.agent.core.tool.ToolInvocationContext;
import com.agent.core.tool.ToolRiskLevel;
import com.agent.core.tool.ToolResultStatus;
import com.agent.web.capability.CapabilityManagementAuditEvent;
import com.agent.web.capability.InstallationScope;
import com.agent.web.identity.Actor;
import com.agent.web.mcp.installation.McpInstallationCommand;
import com.agent.web.mcp.installation.McpInstallationRecord;
import com.agent.web.mcp.installation.McpInstallationStatus;
import com.agent.web.mcp.installation.McpNetworkMode;
import com.agent.web.mcp.installation.McpPreparedMaterialRecord;
import com.agent.web.mcp.installation.McpSourceSnapshot;
import com.agent.web.mcp.installation.WorkspaceMountMode;
import com.agent.web.persistence.JdbcConversationRepository;
import com.agent.web.persistence.JdbcMcpInstallationRepository;
import com.agent.web.workspace.WorkspaceAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 Docker stdio MCP 与 PostgreSQL V1-V9 的启停闭环验证。 */
class McpInstallationRuntimeDockerLifecycleIntegrationTest {
    private static final String ACTOR_USER_ID = "mcp-lifecycle-user";
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void startDocker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker Engine 不可用，跳过真实 MCP 生命周期测试");
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        if (POSTGRES.isRunning()) POSTGRES.stop();
    }

    @Test
    void governedPrepareStartsExecutesAndStopsRealDockerMcpWithPersistentBindings(@TempDir Path workspaceRoot) throws Exception {
        DataSource dataSource = dataSource();
        Flyway.configure().dataSource(dataSource).load().migrate();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("truncate table agent_mcp_installations, agent_mcp_installation_snapshots, agent_capability_management_audit, "
                + "agent_workspace_members, agent_workspaces, agent_users cascade").update();
        ObjectMapper json = new ObjectMapper();
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcMcpInstallationRepository installations = new JdbcMcpInstallationRepository(jdbc, transactions, json);
        JdbcConversationRepository workspaces = new JdbcConversationRepository(jdbc, transactions, Clock.systemUTC());
        Actor actor = new Actor(ACTOR_USER_ID, "MCP Lifecycle User");
        UUID workspaceId = UUID.randomUUID();
        workspaces.ensureDefaultWorkspace(workspaceId, actor, "MCP Lifecycle", workspaceRoot, "mcp-lifecycle", Instant.EPOCH);
        WorkspaceAccessService access = new WorkspaceAccessService(workspaces, workspaceRoot, Clock.systemUTC());

        UUID snapshotId = UUID.randomUUID();
        UUID installationId = UUID.randomUUID();
        McpSourceSnapshot snapshot = new McpSourceSnapshot(snapshotId, "fixture", "src/fixture",
                URI.create("https://example.invalid/fixture"), "0123456789012345678901234567890123456789", Map.of(),
                "a".repeat(64), "2026.7.4", "controlled fixture", "MIT", "npx",
                List.of("-y", "@modelcontextprotocol/server-everything@2026.7.4"), "mcp-server-everything", List.of(),
                "controlled fixture", Instant.EPOCH);
        McpInstallationRecord installation = new McpInstallationRecord(installationId, snapshotId,
                InstallationScope.WORKSPACE, workspaceId, ACTOR_USER_ID, McpInstallationStatus.STOPPED,
                "b".repeat(64), Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, ToolRiskLevel.HIGH,
                Set.of(RequiredCapability.TOOL), WorkspaceMountMode.READ_ONLY, McpNetworkMode.NONE,
                "node:22-alpine", true, null, null, null, 0);
        installations.confirmInstallation(new McpInstallationCommand(snapshot, installation,
                audit(installationId, workspaceId, snapshot, "MCP_INSTALLATION_CONFIRMED", "STOPPED", "STOPPED")));
        Path materialRoot = java.nio.file.Files.createDirectory(workspaceRoot.resolve("mcp-materials"));
        McpInstallationRecord prepared;
        try (DockerMcpMaterialPreparationRunner preparationRunner = new DockerMcpMaterialPreparationRunner(
                materialRoot, "node:22-alpine", "", new ObjectMapper(), Clock.systemUTC())) {
            McpMaterialPreparationService preparation = new McpMaterialPreparationService(() -> actor, access, installations,
                    preparationRunner, Clock.systemUTC());
            prepared = preparation.prepare(workspaceId, installationId, installation.version());
        }
        assertThat(prepared.status()).isEqualTo(McpInstallationStatus.STOPPED);
        McpPreparedMaterialRecord preparedMaterial = installations.findPreparedMaterial(snapshotId).orElseThrow();

        try (DefaultToolRegistry registry = new DefaultToolRegistry(); DockerMcpStdioRunner runner = new DockerMcpStdioRunner()) {
            McpRuntimeMaterialProvider materials = new FileSystemMcpRuntimeMaterialProvider(materialRoot,
                    source -> installations.findPreparedMaterial(source.snapshotId()).map(value ->
                            new McpRuntimeMaterialProvider.PreparedMaterial(value.directory(), value.sha256(), value.command(), value.arguments()))
                            .orElse(null));
            McpInstallationRuntime runtime = new McpInstallationRuntime(() -> actor, access, installations, materials,
                    McpRuntimeSecretProvider.declaredNamesOnly(), runner, registry, json,
                    new McpInstallationRuntime.McpRuntimeConfiguration("2025-06-18", "agent4j", "test",
                            "/mcp-material", "", "", "/workspace", 128L * 1024 * 1024, 100_000_000L, 64,
                            1_048_576, 4_194_304, 1_048_576, Duration.ofSeconds(120), Duration.ofSeconds(60), Duration.ofSeconds(30)),
                    Clock.systemUTC());
            McpInstallationRecord running = runtime.start(workspaceId, installationId,
                    new McpInstallationRuntime.LifecycleRequest(prepared.version(), workspaceId, Map.of()));

            assertThat(running.status()).isEqualTo(McpInstallationStatus.RUNNING);
            assertThat(running.containerId()).isNotBlank();
            try (DockerClient docker = dockerClient()) {
                var inspected = docker.inspectContainerCmd(running.containerId()).exec();
                assertThat(inspected.getHostConfig().getNetworkMode()).isEqualTo("none");
                assertThat(inspected.getHostConfig().getReadonlyRootfs()).isTrue();
                assertThat(inspected.getConfig().getEnv())
                        .doesNotContain("MCP_TOKEN=")
                        .doesNotContain("MCP_TOKEN");
                assertThat(inspected.getConfig().getCmd()).containsExactly("node",
                        "/mcp-material/" + preparedMaterial.command());
                assertThat(inspected.getMounts()).anySatisfy(mount -> {
                    assertThat(((Volume) mount.getDestination()).getPath()).isEqualTo("/mcp-material");
                    assertThat(mount.getRW()).isFalse();
                });
                assertThat(inspected.getMounts()).anySatisfy(mount -> {
                    assertThat(((Volume) mount.getDestination()).getPath()).isEqualTo("/workspace");
                    assertThat(mount.getRW()).isFalse();
                });
            }
            assertThat(installations.findInstallation(installationId, ACTOR_USER_ID, workspaceId).orElseThrow().bindings())
                    .anySatisfy(binding -> {
                        assertThat(binding.remoteToolName()).isEqualTo("echo");
                        assertThat(binding.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
                        assertThat(binding.requiredCapabilities()).containsExactly(RequiredCapability.TOOL);
                    });
            String echoToolName = installations.findInstallation(installationId, ACTOR_USER_ID, workspaceId).orElseThrow().bindings().stream()
                    .filter(binding -> binding.remoteToolName().equals("echo")).findFirst().orElseThrow().localToolName();
            assertThat(registry.execute(new ToolCall("call-1", echoToolName,
                    json.createObjectNode().put("message", "real")), new ToolInvocationContext(UUID.randomUUID(), "ops", ACTOR_USER_ID,
                    workspaceRoot, Set.of(RequiredCapability.TOOL), true)).status()).isEqualTo(ToolResultStatus.SUCCEEDED);

            McpInstallationRecord stopped = runtime.stop(workspaceId, installationId,
                    new McpInstallationRuntime.LifecycleRequest(running.version(), workspaceId, Map.of()));
            assertThat(stopped.status()).isEqualTo(McpInstallationStatus.STOPPED);
            assertThat(stopped.containerId()).isNull();
            assertThat(installations.findInstallation(installationId, ACTOR_USER_ID, workspaceId).orElseThrow().bindings()).isEmpty();
            assertThat(registry.find(echoToolName)).isEmpty();
            runtime.close();
        }
    }

    private static CapabilityManagementAuditEvent audit(UUID installationId, UUID workspaceId, McpSourceSnapshot snapshot,
                                                         String type, String from, String to) {
        return new CapabilityManagementAuditEvent(type, ACTOR_USER_ID, workspaceId, installationId, null, null,
                snapshot.commitSha(), "SUCCESS", Instant.now(), UUID.randomUUID(), from, to, "");
    }

    private static DataSource dataSource() {
        DriverManagerDataSource value = new DriverManagerDataSource();
        value.setDriverClassName(POSTGRES.getDriverClassName());
        value.setUrl(POSTGRES.getJdbcUrl());
        value.setUsername(POSTGRES.getUsername());
        value.setPassword(POSTGRES.getPassword());
        return value;
    }

    private static DockerClient dockerClient() {
        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        DockerHttpClient http = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost()).sslConfig(config.getSSLConfig()).build();
        return DockerClientImpl.getInstance(config, http);
    }

}
