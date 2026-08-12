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
import java.nio.file.Files;
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
    void preparesStartsExecutesAndStopsRealDockerMcpWithPersistentBindings(@TempDir Path workspaceRoot) throws Exception {
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
        Path materialDirectory = Files.createDirectory(workspaceRoot.resolve("mcp-material"));
        Files.writeString(materialDirectory.resolve("fixture.mjs"), fixtureServer());
        try {
            Files.setPosixFilePermissions(materialDirectory.resolve("fixture.mjs"),
                    java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
        } catch (UnsupportedOperationException ignored) {
            // Docker Desktop 映射的 Windows bind 仍由容器挂载权限控制。
        }
        McpSourceSnapshot snapshot = new McpSourceSnapshot(snapshotId, "fixture", "src/fixture",
                URI.create("https://example.invalid/fixture"), "0123456789012345678901234567890123456789", Map.of(),
                "a".repeat(64), "1.0.0", "controlled fixture", "MIT", "npx",
                List.of("-y", "fixture@1.0.0"), "fixture", List.of(), "controlled fixture", Instant.EPOCH);
        McpInstallationRecord installation = new McpInstallationRecord(installationId, snapshotId,
                InstallationScope.WORKSPACE, workspaceId, ACTOR_USER_ID, McpInstallationStatus.STOPPED,
                "b".repeat(64), Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, ToolRiskLevel.HIGH,
                Set.of(RequiredCapability.TOOL), WorkspaceMountMode.READ_ONLY, McpNetworkMode.NONE,
                "node:22-alpine", true, null, null, null, 0);
        installations.confirmInstallation(new McpInstallationCommand(snapshot, installation,
                audit(installationId, workspaceId, snapshot, "MCP_INSTALLATION_CONFIRMED", "STOPPED", "STOPPED")));
        String materialSha = McpRuntimeMaterialProvider.sha256(materialDirectory);
        McpInstallationRecord prepared = installations.completeMaterialPreparation(installationId, ACTOR_USER_ID, workspaceId, 0,
                new McpPreparedMaterialRecord(materialDirectory, materialSha, "fixture.mjs", List.of(), Instant.now()),
                audit(installationId, workspaceId, snapshot, "MCP_MATERIAL_PREPARED", "STOPPED", "STOPPED"));
        assertThat(prepared.status()).isEqualTo(McpInstallationStatus.STOPPED);

        try (DefaultToolRegistry registry = new DefaultToolRegistry(); DockerMcpStdioRunner runner = new DockerMcpStdioRunner()) {
            McpRuntimeMaterialProvider materials = new FileSystemMcpRuntimeMaterialProvider(workspaceRoot,
                    source -> installations.findPreparedMaterial(source.snapshotId()).map(value ->
                            new McpRuntimeMaterialProvider.PreparedMaterial(value.directory(), value.sha256(), value.command(), value.arguments()))
                            .orElse(null));
            McpInstallationRuntime runtime = new McpInstallationRuntime(() -> actor, access, installations, materials,
                    McpRuntimeSecretProvider.declaredNamesOnly(), runner, registry, json,
                    new McpInstallationRuntime.McpRuntimeConfiguration("2025-06-18", "agent4j", "test",
                            "/mcp-material", "", "", "/workspace", 128L * 1024 * 1024, 100_000_000L, 64,
                            4096, 4096, 4096, Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(30)),
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
                    .singleElement().satisfies(binding -> {
                        assertThat(binding.remoteToolName()).isEqualTo("echo");
                        assertThat(binding.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
                        assertThat(binding.requiredCapabilities()).containsExactly(RequiredCapability.TOOL);
                    });
            assertThat(registry.execute(new ToolCall("call-1", "mcp." + installationId.toString().replace("-", "") + ".echo",
                    json.createObjectNode().put("text", "real")), new ToolInvocationContext(UUID.randomUUID(), "ops", ACTOR_USER_ID,
                    workspaceRoot, Set.of(RequiredCapability.TOOL), true)).status()).isEqualTo(ToolResultStatus.SUCCEEDED);

            McpInstallationRecord stopped = runtime.stop(workspaceId, installationId,
                    new McpInstallationRuntime.LifecycleRequest(running.version(), workspaceId, Map.of()));
            assertThat(stopped.status()).isEqualTo(McpInstallationStatus.STOPPED);
            assertThat(stopped.containerId()).isNull();
            assertThat(installations.findInstallation(installationId, ACTOR_USER_ID, workspaceId).orElseThrow().bindings()).isEmpty();
            assertThat(registry.find("mcp." + installationId.toString().replace("-", "") + ".echo")).isEmpty();
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

    private static String fixtureServer() {
        return """
                #!/usr/bin/env node
                import readline from 'node:readline';
                const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
                rl.on('line', line => {
                  const request = JSON.parse(line);
                  if (!Object.hasOwn(request, 'id')) return;
                  let result;
                  if (request.method === 'initialize') {
                    result = { protocolVersion: '2025-06-18', capabilities: {}, serverInfo: { name: 'fixture', version: '1.0.0' } };
                  } else if (request.method === 'tools/list') {
                    result = { tools: [{ name: 'echo', description: 'Echoes text', inputSchema: { type: 'object', properties: { text: { type: 'string' } }, required: ['text'] } }] };
                  } else if (request.method === 'tools/call') {
                    result = { content: [{ type: 'text', text: request.params.arguments.text }], isError: false };
                  } else {
                    process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: request.id, error: { code: -32601, message: 'unknown' } }) + '\\n'); return;
                  }
                  process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: request.id, result }) + '\\n');
                });
                """;
    }
}
