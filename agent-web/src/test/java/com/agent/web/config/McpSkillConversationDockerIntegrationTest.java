package com.agent.web.config;

import com.agent.core.engine.AgentRunService;
import com.agent.core.engine.AgentState;
import com.agent.core.engine.ExecutionBudget;
import com.agent.core.engine.GraphRegistry;
import com.agent.core.engine.InterruptPolicy;
import com.agent.core.engine.RunCheckpoint;
import com.agent.core.engine.RunStatus;
import com.agent.core.engine.StateGraph;
import com.agent.core.harness.HarnessEvent;
import com.agent.core.harness.HarnessEventType;
import com.agent.core.harness.HarnessHookChain;
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
import com.agent.web.capability.CapabilityManagementAuditSink;
import com.agent.web.capability.InstallationScope;
import com.agent.web.conversation.ConversationService;
import com.agent.web.identity.Actor;
import com.agent.web.mcp.installation.InstalledMcpCatalogProvider;
import com.agent.web.mcp.installation.McpInstallationRecord;
import com.agent.web.mcp.installation.McpInstallationStatus;
import com.agent.web.mcp.installation.McpInstallationService;
import com.agent.web.mcp.installation.WorkspaceMountMode;
import com.agent.web.mcp.catalog.OfficialMcpCatalogClient;
import com.agent.web.mcp.catalog.OfficialMcpServerRecord;
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
import com.agent.web.skill.GitHubSkillCatalogClient;
import com.agent.web.skill.GitHubSkillInstallationService;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 Docker MCP、通过受控目录确认的 Skill 与受控 OpenAI function call 的会话闭环验证。 */
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

        OfficialMcpCatalogClient officialCatalog = new OfficialMcpCatalogClient(
                McpSkillConversationDockerIntegrationTest::officialCatalogResponse, json,
                URI.create("https://api.github.com/repos/modelcontextprotocol/servers/"), "main",
                Duration.ofSeconds(10), 512_000, Duration.ofMinutes(5));
        OfficialMcpServerRecord officialServer = officialCatalog.fetchCatalog().stream()
                .filter(value -> "everything".equals(value.serviceId())).findFirst().orElseThrow();
        McpInstallationService installationService = new McpInstallationService(
                () -> actor, access, mcps, CapabilityManagementAuditSink.noop(), clock,
                Duration.ofMinutes(5), UUID::randomUUID, "node:22-alpine");
        var mcpPreview = installationService.preview(workspaceId, officialServer,
                InstallationScope.WORKSPACE, workspaceId, ToolRiskLevel.LOW,
                Set.of(RequiredCapability.TOOL), WorkspaceMountMode.NONE);
        McpInstallationRecord installation = installationService.confirm(workspaceId,
                mcpPreview.previewId(), mcpPreview.confirmationToken(), InstallationScope.WORKSPACE, workspaceId);
        UUID installationId = installation.installationId();
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

            GitHubSkillCatalogClient githubCatalog = new GitHubSkillCatalogClient(
                    (uri, timeout, maxBytes) -> githubSkillResponse(uri, echoToolName), json,
                    URI.create("https://api.github.com/"), Duration.ofSeconds(10), 512_000);
            GitHubSkillInstallationService skillInstallationService = new GitHubSkillInstallationService(
                    githubCatalog, registry, () -> actor, access, skills, CapabilityManagementAuditSink.noop(),
                    clock, Duration.ofMinutes(5), UUID::randomUUID);
            var skillPreview = skillInstallationService.preview(workspaceId, "agent4j/mcp-echo-skill",
                    InstallationScope.WORKSPACE, workspaceId);
            skillInstallationService.confirm(workspaceId, skillPreview.previewId(), skillPreview.confirmationToken(),
                    InstallationScope.WORKSPACE, workspaceId);
            InstalledSkillCatalogProvider skillCatalog = new InstalledSkillCatalogProvider(skills, registry, json,
                    List.of(), CapabilityManagementAuditSink.noop());
            InstalledMcpCatalogProvider mcpCatalog = new InstalledMcpCatalogProvider(mcps, registry);

            UUID actorSecondWorkspaceId = UUID.randomUUID();
            Path actorSecondWorkspacePath = Files.createDirectory(workspaceRoot.resolve("actor-second-workspace"));
            conversations.ensureDefaultWorkspace(actorSecondWorkspaceId, actor, "MCP Skill E2E Second",
                    actorSecondWorkspacePath, "mcp-skill-e2e-second", Instant.EPOCH);
            assertThat(skillCatalog.resolve(ACTOR_USER_ID, actorSecondWorkspaceId).definitions()).isEmpty();
            assertThat(mcpCatalog.resolve(ACTOR_USER_ID, actorSecondWorkspaceId).bindings()).isEmpty();

            UUID otherWorkspaceId = UUID.randomUUID();
            Actor otherActor = new Actor("mcp-skill-e2e-other-user", "MCP Skill E2E Other User");
            Path otherWorkspacePath = Files.createDirectory(workspaceRoot.resolve("other-workspace"));
            conversations.ensureDefaultWorkspace(otherWorkspaceId, otherActor, "MCP Skill E2E Other",
                    otherWorkspacePath, "mcp-skill-e2e-other", Instant.EPOCH);
            assertThat(skillCatalog.resolve(otherActor.userId(), otherWorkspaceId).definitions()).isEmpty();
            assertThat(mcpCatalog.resolve(otherActor.userId(), otherWorkspaceId).bindings()).isEmpty();

            List<HarnessEvent> harnessEvents = new ArrayList<>();
            try (FunctionCallServer model = new FunctionCallServer(json, modelRequests, echoToolName)) {
                ModelRouter router = router(json, model.baseUrl());
                var toolAgent = new ToolAgentNode(router, registry, json, 2, true);
                var graphRegistry = new GraphRegistry(Map.of("code-agent", () -> new StateGraph(
                        new ExecutionBudget(Duration.ofSeconds(90), Duration.ofSeconds(30), 10_000, 4, 2),
                        InterruptPolicy.never(), new HarnessHookChain(List.of(harnessEvents::add)))
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
                    assertThat(toolAudits).filteredOn(audit -> echoToolName.equals(audit.toolName()))
                            .singleElement().satisfies(audit -> {
                                assertThat(audit.runId()).isEqualTo(turn.runId());
                                assertThat(audit.nodeName()).isEqualTo("tool-agent");
                                assertThat(audit.userId()).isEqualTo(ACTOR_USER_ID);
                                assertThat(audit.callId()).isEqualTo("echo-call");
                                assertThat(audit.riskLevel()).contains(ToolRiskLevel.LOW);
                                assertThat(audit.status().name()).isEqualTo("SUCCEEDED");
                            });
                    assertThat(model.firstRequestToolNames())
                            .contains(model.expectedProtocolToolName());
                    assertThat(model.firstRequestToolNames()).noneMatch(String::isBlank);
                    assertThat(harnessEvents).filteredOn(event -> event.runId().equals(turn.runId())
                                    && "tool-agent".equals(event.nodeName())
                                    && (event.eventType() == HarnessEventType.BEFORE_TOOL
                                    || event.eventType() == HarnessEventType.AFTER_TOOL))
                            .extracting(HarnessEvent::eventType)
                            .containsExactly(HarnessEventType.BEFORE_TOOL, HarnessEventType.AFTER_TOOL);
                    assertThat(harnessEvents).filteredOn(event -> event.runId().equals(turn.runId())
                                    && "tool-agent".equals(event.nodeName())
                                    && event.eventType() == HarnessEventType.BEFORE_TOOL)
                            .singleElement().satisfies(event -> assertThat(event.metadata())
                                    .containsEntry("toolName", echoToolName)
                                    .containsEntry("callId", "echo-call")
                                    .containsEntry("riskLevel", ToolRiskLevel.LOW.name()));
                    assertThat(harnessEvents).filteredOn(event -> event.runId().equals(turn.runId())
                                    && "tool-agent".equals(event.nodeName())
                                    && event.eventType() == HarnessEventType.AFTER_TOOL)
                            .singleElement().satisfies(event -> assertThat(event.metadata())
                                    .containsEntry("toolName", echoToolName)
                                    .containsEntry("callId", "echo-call")
                                    .containsEntry("riskLevel", ToolRiskLevel.LOW.name()));
                    assertThat(traces).filteredOn(trace -> trace.runId().equals(turn.runId()))
                            .anySatisfy(trace -> assertThat(trace).isInstanceOf(TraceEvent.NodeStarted.class)
                                    .extracting(value -> ((TraceEvent.NodeStarted) value).nodeName())
                                    .isEqualTo("tool-agent"));
                    assertThat(traces).filteredOn(trace -> trace.runId().equals(turn.runId()))
                            .anySatisfy(trace -> assertThat(trace).isInstanceOf(TraceEvent.NodeCompleted.class)
                                    .extracting(value -> ((TraceEvent.NodeCompleted) value).nodeName())
                                    .isEqualTo("tool-agent"));
                    assertThat(traces).filteredOn(trace -> trace.runId().equals(turn.runId()))
                            .anySatisfy(trace -> assertThat(trace).isInstanceOf(TraceEvent.Completed.class));
                    assertThat(modelRequests).hasValue(2);

                    McpInstallationRecord stopped = runtime.stop(workspaceId, installationId,
                            new McpInstallationRuntime.LifecycleRequest(running.version(), workspaceId, Map.of()));
                    assertThat(stopped.status()).isEqualTo(McpInstallationStatus.STOPPED);
                    assertThat(mcps.findInstallation(installationId, ACTOR_USER_ID, workspaceId).orElseThrow().bindings())
                            .isEmpty();
                    assertThat(registry.find(echoToolName)).isEmpty();
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

    private static GitHubSkillCatalogClient.HttpResponse githubSkillResponse(URI uri, String toolName) {
        String path = uri.getPath();
        String commit = "0123456789012345678901234567890123456789";
        if (path.endsWith("/repos/agent4j/mcp-echo-skill")) {
            return new GitHubSkillCatalogClient.HttpResponse(200, """
                    {"full_name":"agent4j/mcp-echo-skill","html_url":"https://github.com/agent4j/mcp-echo-skill",
                    "default_branch":"main","description":"受控 MCP 回显 Skill","license":{"spdx_id":"MIT"}}
                    """);
        }
        if (path.endsWith("/repos/agent4j/mcp-echo-skill/commits/main")) {
            return new GitHubSkillCatalogClient.HttpResponse(200, "{\"sha\":\"" + commit + "\"}");
        }
        if (path.endsWith("/repos/agent4j/mcp-echo-skill/contents/SKILL.md")) {
            String content = skillContent(toolName);
            return new GitHubSkillCatalogClient.HttpResponse(200, "{\"path\":\"SKILL.md\",\"type\":\"file\","
                    + "\"encoding\":\"base64\",\"sha\":\"mcp-echo-skill-blob\",\"content\":\""
                    + java.util.Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)) + "\"}");
        }
        throw new AssertionError("未预期 GitHub Skill 目录请求: " + uri);
    }

    private static String skillContent(String toolName) {
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
        return content;
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

    private static OfficialMcpCatalogClient.HttpResponse officialCatalogResponse(
            URI uri, Duration timeout, int maxBytes, String etag) {
        String path = uri.getPath();
        String query = uri.getQuery();
        String commit = "0123456789012345678901234567890123456789";
        if (path.endsWith("/commits/main")) return officialResponse("{\"sha\":\"" + commit + "\"}");
        if (path.endsWith("/contents") && (query == null || query.contains("ref=" + commit))) {
            return officialResponse("[{\"name\":\"src\",\"type\":\"dir\",\"path\":\"src\",\"sha\":\"src-blob\"}]");
        }
        if (path.endsWith("/contents/src")) {
            return officialResponse("[{\"name\":\"everything\",\"type\":\"dir\",\"path\":\"src/everything\",\"sha\":\"everything-blob\"}]");
        }
        if (path.endsWith("/contents/src/everything")) {
            return officialResponse("[{\"name\":\"package.json\",\"type\":\"file\",\"path\":\"src/everything/package.json\",\"sha\":\"package-blob\"},"
                    + "{\"name\":\"README.md\",\"type\":\"file\",\"path\":\"src/everything/README.md\",\"sha\":\"readme-blob\"}]");
        }
        if (path.endsWith("/contents/src/everything/package.json")) {
            return officialContentResponse("package-blob", "{\"name\":\"@modelcontextprotocol/server-everything\",\"version\":\"2026.7.4\",\"license\":\"MIT\",\"bin\":{\"mcp-server-everything\":\"dist/index.js\"}}");
        }
        if (path.endsWith("/contents/src/everything/README.md")) return officialContentResponse("readme-blob", "# Everything");
        throw new AssertionError("未预期官方 MCP 目录请求: " + uri);
    }

    private static OfficialMcpCatalogClient.HttpResponse officialContentResponse(String sha, String source) {
        return officialResponse("{\"sha\":\"" + sha + "\",\"encoding\":\"base64\",\"content\":\""
                + java.util.Base64.getEncoder().encodeToString(source.getBytes(StandardCharsets.UTF_8)) + "\"}");
    }

    private static OfficialMcpCatalogClient.HttpResponse officialResponse(String body) {
        return new OfficialMcpCatalogClient.HttpResponse(200, body, "test-etag");
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
        private final String expectedProtocolToolName;
        private final AtomicReference<List<String>> firstRequestToolNames = new AtomicReference<>(List.of());

        private FunctionCallServer(ObjectMapper json, AtomicInteger requests, String expectedLocalToolName) throws Exception {
            this.expectedProtocolToolName = expectedLocalToolName.replaceAll("[^A-Za-z0-9]", "_");
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
                    List<String> functionToolNames = new java.util.ArrayList<>();
                    request.path("tools").forEach(tool -> functionToolNames.add(tool.path("function").path("name").asText()));
                    firstRequestToolNames.set(List.copyOf(functionToolNames));
                    if (!functionToolNames.contains(expectedProtocolToolName)) {
                        throw new AssertionError("OpenAI 请求工具集合未包含运行时 echo binding: " + expectedProtocolToolName);
                    }
                    message.put("content", "");
                    var toolCall = message.putArray("tool_calls").addObject();
                    toolCall.put("id", "echo-call");
                    toolCall.put("type", "function");
                    toolCall.putObject("function").put("name", expectedProtocolToolName)
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

        private List<String> firstRequestToolNames() {
            return firstRequestToolNames.get();
        }

        private String expectedProtocolToolName() {
            return expectedProtocolToolName;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
