package com.agent.web.config;

import com.agent.core.engine.AgentRunService;
import com.agent.core.engine.AgentState;
import com.agent.core.engine.ExecutionBudget;
import com.agent.core.engine.GraphRegistry;
import com.agent.core.engine.RunCheckpoint;
import com.agent.core.engine.RunStatus;
import com.agent.core.engine.StateGraph;
import com.agent.core.intent.RequiredCapability;
import com.agent.core.llm.LlmClient;
import com.agent.core.llm.ModelEndpoint;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.TaskType;
import com.agent.core.mcp.McpCatalogSnapshotCodec;
import com.agent.core.nodes.PlannerNode;
import com.agent.core.nodes.ToolAgentNode;
import com.agent.core.skill.SkillCatalogSnapshotCodec;
import com.agent.core.tool.DefaultToolAuthorizer;
import com.agent.core.tool.DefaultToolRegistry;
import com.agent.core.tool.JacksonToolSchemaValidator;
import com.agent.core.tool.ToolAuditEvent;
import com.agent.core.tool.ToolRiskLevel;
import com.agent.core.trace.TraceEvent;
import com.agent.web.capability.CapabilityManagementAuditEvent;
import com.agent.web.capability.CapabilityManagementAuditSink;
import com.agent.web.capability.InstallationScope;
import com.agent.web.conversation.ConversationService;
import com.agent.web.identity.Actor;
import com.agent.web.mcp.installation.InstalledMcpCatalogProvider;
import com.agent.web.mcp.installation.McpInstallationCommand;
import com.agent.web.mcp.installation.McpInstallationRecord;
import com.agent.web.mcp.installation.McpInstallationStatus;
import com.agent.web.mcp.installation.McpNetworkMode;
import com.agent.web.mcp.installation.McpSourceSnapshot;
import com.agent.web.mcp.installation.WorkspaceMountMode;
import com.agent.web.mcp.runtime.DockerMcpMaterialPreparationRunner;
import com.agent.web.mcp.runtime.DockerMcpStdioRunner;
import com.agent.web.mcp.runtime.FileSystemMcpRuntimeMaterialProvider;
import com.agent.web.mcp.runtime.McpInstallationRuntime;
import com.agent.web.mcp.runtime.McpMaterialPreparationService;
import com.agent.web.mcp.runtime.McpRuntimeMaterialProvider;
import com.agent.web.mcp.runtime.McpRuntimeSecretProvider;
import com.agent.web.persistence.JdbcCheckpointer;
import com.agent.web.persistence.JdbcConversationRepository;
import com.agent.web.persistence.JdbcMcpInstallationRepository;
import com.agent.web.persistence.JdbcSkillInstallationRepository;
import com.agent.web.skill.InstalledSkillCatalogProvider;
import com.agent.web.skill.SkillInstallationRecord;
import com.agent.web.skill.SkillInstallationStatus;
import com.agent.web.skill.SkillSnapshotRecord;
import com.agent.web.workspace.WorkspaceAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
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
import org.springframework.web.client.RestClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 Docker MCP、已批准 Skill 与受控 OpenAI function call 的会话闭环验证。 */
class McpSkillConversationDockerIntegrationTest {
    private static final String ACTOR_USER_ID = "mcp-skill-e2e-user";
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void startDocker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker Engine 不可用，跳过真实 MCP Skill 会话测试");
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @Test
    void submitsConversationWithApprovedSkillAndRunningMcpThenExecutesRealEcho(@TempDir Path workspaceRoot)
            throws Exception {
        ObjectMapper json = new ObjectMapper();
        DataSource dataSource = dataSource();
        Flyway.configure().dataSource(dataSource).load().migrate();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("truncate table agent_checkpoints, agent_runs, agent_conversation_turns, agent_conversations, "
                + "agent_skill_installations, agent_skill_snapshots, agent_mcp_tool_bindings, "
                + "agent_mcp_installations, agent_mcp_installation_snapshots, agent_capability_management_audit, "
                + "agent_workspace_members, agent_workspaces, agent_users cascade").update();
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Clock clock = Clock.systemUTC();
        JdbcConversationRepository conversations = new JdbcConversationRepository(jdbc, transactions, clock);
        JdbcMcpInstallationRepository mcps = new JdbcMcpInstallationRepository(jdbc, transactions, json);
        JdbcSkillInstallationRepository skills = new JdbcSkillInstallationRepository(jdbc, transactions, json);
        Actor actor = new Actor(ACTOR_USER_ID, "MCP Skill E2E User");
        UUID workspaceId = UUID.randomUUID();
        conversations.ensureDefaultWorkspace(workspaceId, actor, "MCP Skill E2E", workspaceRoot,
                "mcp-skill-e2e", Instant.EPOCH);
        WorkspaceAccessService access = new WorkspaceAccessService(conversations, workspaceRoot, clock);
        List<ToolAuditEvent> toolAudits = new ArrayList<>();
        List<TraceEvent> traces = new ArrayList<>();
        AtomicInteger modelRequests = new AtomicInteger();

        UUID snapshotId = UUID.randomUUID();
        UUID installationId = UUID.randomUUID();
        McpSourceSnapshot source = new McpSourceSnapshot(snapshotId, "everything", "src/everything",
                URI.create("https://example.invalid/everything"), "0123456789012345678901234567890123456789", Map.of(),
                "a".repeat(64), "2026.7.4", "controlled everything fixture", "MIT", "npx",
                List.of("-y", "@modelcontextprotocol/server-everything@2026.7.4"), "mcp-server-everything", List.of(),
                "controlled everything fixture", Instant.EPOCH);
        McpInstallationRecord installation = new McpInstallationRecord(installationId, snapshotId,
                InstallationScope.WORKSPACE, workspaceId, ACTOR_USER_ID, McpInstallationStatus.STOPPED,
                "b".repeat(64), Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, ToolRiskLevel.LOW,
                Set.of(RequiredCapability.TOOL), WorkspaceMountMode.READ_ONLY, McpNetworkMode.NONE,
                "node:22-alpine", true, null, null, null, 0);
        mcps.confirmInstallation(new McpInstallationCommand(source, installation,
                mcpAudit(installationId, workspaceId, source, "MCP_INSTALLATION_CONFIRMED", "STOPPED", "STOPPED")));
        Path materialRoot = Files.createDirectory(workspaceRoot.resolve("mcp-materials"));

        try (DockerMcpMaterialPreparationRunner preparationRunner = new DockerMcpMaterialPreparationRunner(
                materialRoot, "node:22-alpine", "", json, clock);
             DefaultToolRegistry registry = new DefaultToolRegistry(new JacksonToolSchemaValidator(),
                     new DefaultToolAuthorizer(), toolAudits::add, json, System::nanoTime);
             DockerMcpStdioRunner dockerRunner = new DockerMcpStdioRunner()) {
            McpMaterialPreparationService preparation = new McpMaterialPreparationService(
                    () -> actor, access, mcps, preparationRunner, clock);
            McpInstallationRecord prepared = preparation.prepare(workspaceId, installationId, installation.version());
            McpRuntimeMaterialProvider materials = new FileSystemMcpRuntimeMaterialProvider(materialRoot,
                    value -> mcps.findPreparedMaterial(value.snapshotId()).map(material ->
                            new McpRuntimeMaterialProvider.PreparedMaterial(material.directory(), material.sha256(),
                                    material.command(), material.arguments())).orElse(null));
            McpInstallationRuntime runtime = new McpInstallationRuntime(() -> actor, access, mcps, materials,
                    McpRuntimeSecretProvider.declaredNamesOnly(), dockerRunner, registry, json,
                    runtimeConfiguration(), clock);
            McpInstallationRecord running = runtime.start(workspaceId, installationId,
                    new McpInstallationRuntime.LifecycleRequest(prepared.version(), workspaceId, Map.of()));
            assertThat(running.status()).isEqualTo(McpInstallationStatus.RUNNING);
            String echoToolName = mcps.findInstallation(installationId, ACTOR_USER_ID, workspaceId).orElseThrow()
                    .bindings().stream().filter(binding -> "echo".equals(binding.remoteToolName())).findFirst()
                    .orElseThrow().localToolName();
            assertThat(registry.find(echoToolName)).isPresent();

            installApprovedSkill(skills, workspaceId, echoToolName, source.commitSha());
            InstalledSkillCatalogProvider skillCatalog = new InstalledSkillCatalogProvider(skills, registry, json,
                    List.of(), CapabilityManagementAuditSink.noop());
            InstalledMcpCatalogProvider mcpCatalog = new InstalledMcpCatalogProvider(mcps, registry);

            try (FunctionCallServer model = new FunctionCallServer(json, modelRequests)) {
                ModelRouter router = router(json, model.baseUrl());
                var toolAgent = new ToolAgentNode(router, registry, json, 2, true);
                var graphRegistry = new GraphRegistry(Map.of("code-agent", () -> new StateGraph(
                        new ExecutionBudget(Duration.ofSeconds(90), Duration.ofSeconds(30), 10_000, 4, 2))
                        .addNode("tool-agent", toolAgent)
                        .setEntryPoint("tool-agent")
                        .addEdge("tool-agent", StateGraph.END)));
                JdbcCheckpointer checkpointer = new JdbcCheckpointer(jdbc, transactions, json, clock);
                try (AgentRunService runs = new AgentRunService(checkpointer, graphRegistry, traces::add)) {
                    ConversationService service = new ConversationService(conversations, access,
                            (conversationId, userId, maxTurns, maxCharacters) ->
                                    new com.agent.core.conversation.ConversationContext(List.of(), 0, false),
                            () -> actor, runs::start, null,
                            com.agent.web.audit.ConversationAuditSink.noop(), clock, skillCatalog,
                            new SkillCatalogSnapshotCodec(json), mcpCatalog, new McpCatalogSnapshotCodec(json));
                    UUID conversationId = service.createConversation(workspaceId).conversationId();
                    var turn = service.submitTurn(conversationId, "执行真实 MCP 回显", "");
                    RunCheckpoint completed = awaitTerminal(checkpointer, turn.runId());

                    assertThat(completed.status()).isEqualTo(RunStatus.COMPLETED);
                    assertThat(completed.state().variables())
                            .containsEntry(ToolAgentNode.ACTIVE_SKILLS_KEY, "mcp.echo.skill@1.0.0")
                            .containsKey(ToolAgentNode.SKILL_FINGERPRINT_KEY)
                            .containsEntry(PlannerNode.FINAL_RESPONSE_KEY, "真实 MCP 回显完成");
                    assertThat(completed.state().variables().get(ToolAgentNode.RESULT_KEY)).contains("联合 E2E 回显");
                    assertThat(new SkillCatalogSnapshotCodec(json).decode(
                            completed.state().variables().get(ToolAgentNode.SKILL_CATALOG_SNAPSHOT_KEY),
                            ACTOR_USER_ID, workspaceId, registry).definitions())
                            .extracting(definition -> definition.name()).containsExactly("mcp.echo.skill");
                    assertThat(new McpCatalogSnapshotCodec(json).decode(
                            completed.state().variables().get(ToolAgentNode.MCP_CATALOG_SNAPSHOT_KEY),
                            ACTOR_USER_ID, workspaceId).bindings())
                            .extracting(binding -> binding.localToolName()).contains(echoToolName);
                    assertThat(toolAudits).anySatisfy(audit -> {
                        assertThat(audit.toolName()).isEqualTo(echoToolName);
                        assertThat(audit.status().name()).isEqualTo("SUCCEEDED");
                        assertThat(audit.userId()).isEqualTo(ACTOR_USER_ID);
                    });
                    assertThat(traces).anySatisfy(trace -> assertThat(trace.type().name()).isEqualTo("COMPLETED"));
                    assertThat(modelRequests).hasValue(2);

                    McpInstallationRecord stopped = runtime.stop(workspaceId, installationId,
                            new McpInstallationRuntime.LifecycleRequest(running.version(), workspaceId, Map.of()));
                    assertThat(stopped.status()).isEqualTo(McpInstallationStatus.STOPPED);
                    AgentState revoked = toolAgent.execute(completed.state());
                    assertThat(revoked.variables().get(ToolAgentNode.ERROR_KEY))
                            .contains("Skill 引用工具未注册: " + echoToolName);
                    assertThat(modelRequests).hasValue(2);
                }
            } finally {
                runtime.close();
            }
        }
    }

    private void installApprovedSkill(JdbcSkillInstallationRepository skills, UUID workspaceId,
                                      String toolName, String commitSha) {
        String content = """
                ---
                name: mcp.echo.skill
                version: 1.0.0
                description: 通过受控 MCP 回显文本
                triggers:
                  - 执行真实 MCP 回显
                tools:
                  - %s
                ---
                使用声明的 MCP 工具回显用户请求，并在工具完成后报告结果。
                """.formatted(toolName).strip();
        Instant now = Instant.now();
        SkillSnapshotRecord snapshot = new SkillSnapshotRecord(UUID.randomUUID(),
                URI.create("https://github.com/agent4j/mcp-echo-skill"), "agent4j/mcp-echo-skill", commitSha,
                "mcp-echo-skill-blob", "SKILL.md", "MIT", sha256(content), "通过受控 MCP 回显文本",
                List.of(toolName), content, now);
        SkillInstallationRecord installation = new SkillInstallationRecord(UUID.randomUUID(), snapshot.skillSnapshotId(),
                InstallationScope.WORKSPACE, workspaceId, ACTOR_USER_ID, SkillInstallationStatus.APPROVED,
                "c".repeat(64), now, now, now, 0);
        skills.confirmSkill(snapshot, installation, new CapabilityManagementAuditEvent("SKILL_INSTALLATION_CONFIRMED",
                ACTOR_USER_ID, workspaceId, null, installation.skillInstallationId(), null, commitSha, "SUCCESS", now));
    }

    private ModelRouter router(ObjectMapper json, String baseUrl) {
        LlmClient client = new LlmClient(RestClient.builder().baseUrl(baseUrl).build(), json, "/v1/chat/completions");
        ModelEndpoint endpoint = new ModelEndpoint("controlled-openai", "controlled-model", client,
                CircuitBreaker.ofDefaults("mcp-skill-e2e"));
        return new ModelRouter(Map.of(TaskType.CODE, List.of(endpoint), TaskType.VISION, List.of(endpoint),
                TaskType.QUICK_CLASSIFICATION, List.of(endpoint)));
    }

    private RunCheckpoint awaitTerminal(JdbcCheckpointer checkpointer, UUID runId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            RunCheckpoint current = checkpointer.loadLatest(runId).orElseThrow();
            if (current.status() != RunStatus.RUNNING) {
                return current;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        throw new AssertionError("Run 未在时限内结束: " + runId);
    }

    private McpInstallationRuntime.McpRuntimeConfiguration runtimeConfiguration() {
        return new McpInstallationRuntime.McpRuntimeConfiguration("2025-06-18", "agent4j", "test",
                "/mcp-material", "", "", "/workspace", 128L * 1024 * 1024, 100_000_000L, 64,
                1_048_576, 4_194_304, 1_048_576, Duration.ofSeconds(120), Duration.ofSeconds(60),
                Duration.ofSeconds(30));
    }

    private CapabilityManagementAuditEvent mcpAudit(UUID installationId, UUID workspaceId, McpSourceSnapshot snapshot,
                                                     String type, String from, String to) {
        return new CapabilityManagementAuditEvent(type, ACTOR_USER_ID, workspaceId, installationId, null, null,
                snapshot.commitSha(), "SUCCESS", Instant.now(), UUID.randomUUID(), from, to, "");
    }

    private static String sha256(String content) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static DataSource dataSource() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName(POSTGRES.getDriverClassName());
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUsername(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }

    private static final class FunctionCallServer implements AutoCloseable {
        private final HttpServer server;

        private FunctionCallServer(ObjectMapper json, AtomicInteger requests) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                JsonNode request = json.readTree(exchange.getRequestBody());
                int call = requests.incrementAndGet();
                var response = json.createObjectNode()
                        .put("id", "controlled-" + call)
                        .put("object", "chat.completion")
                        .put("created", 1)
                        .put("model", "controlled-model");
                var choice = response.putArray("choices").addObject();
                choice.put("index", 0);
                var message = choice.putObject("message");
                message.put("role", "assistant");
                if (call == 1) {
                    String exactToolName = request.path("tools").get(0).path("function").path("name").asText();
                    if (exactToolName.isBlank()) {
                        throw new AssertionError("OpenAI 请求未携带 function tool");
                    }
                    message.put("content", "");
                    var toolCall = message.putArray("tool_calls").addObject();
                    toolCall.put("id", "echo-call");
                    toolCall.put("type", "function");
                    toolCall.putObject("function").put("name", exactToolName)
                            .put("arguments", "{\"message\":\"联合 E2E 回显\"}");
                    choice.put("finish_reason", "tool_calls");
                } else {
                    message.put("content", "真实 MCP 回显完成");
                    choice.put("finish_reason", "stop");
                }
                response.putObject("usage").put("prompt_tokens", 1).put("completion_tokens", 1).put("total_tokens", 2);
                byte[] body = json.writeValueAsBytes(response);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
