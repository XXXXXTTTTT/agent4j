package com.agent.web.command;

import com.agent.core.command.CommandAuthorizationDecision;
import com.agent.core.command.CommandContext;
import com.agent.core.command.CommandDispatcher;
import com.agent.core.command.CommandResult;
import com.agent.core.command.InMemoryCommandRegistry;
import com.agent.core.llm.InferenceCapability;
import com.agent.core.llm.TaskType;
import com.agent.web.capability.InstallationScope;
import com.agent.web.conversation.ConversationService;
import com.agent.web.conversation.ConversationTurnRecord;
import com.agent.web.conversation.ConversationTurnStatus;
import com.agent.web.mcp.installation.McpInstallationDetails;
import com.agent.web.mcp.installation.McpInstallationRecord;
import com.agent.web.mcp.installation.McpInstallationService;
import com.agent.web.model.ModelConfigurationService;
import com.agent.web.model.ModelConfigurationSnapshot;
import com.agent.web.model.ModelEndpointRecord;
import com.agent.web.model.ModelGroupRecord;
import com.agent.web.skill.GitHubSkillInstallationService;
import com.agent.web.skill.SkillInstallationRecord;
import com.agent.web.skill.SkillInstallationStatus;
import com.agent.web.workspace.WorkspaceAccessService;
import com.agent.web.workspace.WorkspacePermission;
import com.agent.web.workspace.WorkspaceRecord;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityCommandHandlersTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("0b7c6c08-3e41-4e72-9a76-16da50282f6f");
    private static final UUID MCP_INSTALLATION_ID = UUID.fromString("6d7d40e3-f407-4dfe-ab2b-1ddc4e9c1c1d");
    private static final UUID SKILL_INSTALLATION_ID = UUID.fromString("ea09e62b-4f55-4c95-b69e-7c84aece3f26");
    private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");

    @Test
    void listsCurrentWorkspaceMcpAndSkillInstallationsWithoutCallingAWorkflowBridge() {
        McpInstallationService mcpInstallations = mock(McpInstallationService.class);
        GitHubSkillInstallationService skillInstallations = mock(GitHubSkillInstallationService.class);
        ModelConfigurationService modelConfigurations = mock(ModelConfigurationService.class);
        McpInstallationRecord mcp = new McpInstallationRecord(
                MCP_INSTALLATION_ID, UUID.fromString("0fe4cd61-825d-471a-8e79-0bac4a59f42c"),
                InstallationScope.WORKSPACE, WORKSPACE_ID, "actor-1",
                com.agent.web.mcp.installation.McpInstallationStatus.RUNNING, "token-sha",
                NOW, NOW, NOW, com.agent.core.tool.ToolRiskLevel.HIGH,
                java.util.Set.of(com.agent.core.intent.RequiredCapability.TOOL),
                com.agent.web.mcp.installation.WorkspaceMountMode.NONE,
                com.agent.web.mcp.installation.McpNetworkMode.NONE, "node:22-alpine", true,
                WORKSPACE_ID, "container-1", null, 3);
        SkillInstallationRecord skill = new SkillInstallationRecord(
                SKILL_INSTALLATION_ID, UUID.fromString("a0b4c3c9-e11a-4bbb-8fbd-1b151c31b25e"),
                InstallationScope.WORKSPACE, WORKSPACE_ID, "actor-1", SkillInstallationStatus.APPROVED,
                "token-sha", NOW, NOW, NOW, 2);
        when(mcpInstallations.listDetails(WORKSPACE_ID)).thenReturn(List.of(
                new McpInstallationDetails(mcp, List.of("SERVICE_TOKEN"))));
        when(skillInstallations.list(WORKSPACE_ID)).thenReturn(List.of(skill));
        InMemoryCommandRegistry registry = new InMemoryCommandRegistry();
        registry.replace(CapabilityCommandHandlers.definitions(
                mcpInstallations, skillInstallations, modelConfigurations,
                mock(WorkspaceAccessService.class), mock(ConversationService.class)));
        CommandDispatcher dispatcher = new CommandDispatcher(
                registry, (definition, context) -> CommandAuthorizationDecision.allow(), event -> { });
        CommandContext context = new CommandContext("actor-1", WORKSPACE_ID.toString(), "conversation-1");

        CommandResult mcpResult = dispatcher.dispatch("/mcp", context);
        CommandResult skillResult = dispatcher.dispatch("/skills", context);

        assertThat(mcpResult.status()).isEqualTo(CommandResult.Status.COMPLETED);
        assertThat(mcpResult.message()).isEqualTo("MCP 安装状态");
        assertThat(mcpResult.data()).containsEntry("count", 1);
        assertThat(skillResult.status()).isEqualTo(CommandResult.Status.COMPLETED);
        assertThat(skillResult.message()).isEqualTo("Skill 安装状态");
        assertThat(skillResult.data()).containsEntry("count", 1);
        verify(mcpInstallations).listDetails(WORKSPACE_ID);
        verify(skillInstallations).list(WORKSPACE_ID);
    }

    @Test
    void listsCurrentActorModelGroupsAndEndpointsWithoutProviderSecrets() {
        McpInstallationService mcpInstallations = mock(McpInstallationService.class);
        GitHubSkillInstallationService skillInstallations = mock(GitHubSkillInstallationService.class);
        ModelConfigurationService modelConfigurations = mock(ModelConfigurationService.class);
        UUID endpointId = UUID.fromString("4c37b9fb-2211-47b8-976d-0f643986d2fe");
        UUID groupId = UUID.fromString("75b0d770-af8f-41c0-a09d-1a44bd2ca79f");
        when(modelConfigurations.snapshot()).thenReturn(new ModelConfigurationSnapshot(
                List.of(),
                List.of(new ModelEndpointRecord(endpointId,
                        UUID.fromString("c6ee32bf-69cc-4957-a587-74b5a65b713d"),
                        "代码端点", "gpt-5.4", Set.of(InferenceCapability.TOOL_CALLING),
                        1, 3, true, NOW, NOW)),
                List.of(new ModelGroupRecord(groupId, "actor-1", "代码模型组", TaskType.CODE,
                        List.of(endpointId), NOW, NOW))));
        InMemoryCommandRegistry registry = new InMemoryCommandRegistry();
        registry.replace(CapabilityCommandHandlers.definitions(
                mcpInstallations, skillInstallations, modelConfigurations,
                mock(WorkspaceAccessService.class), mock(ConversationService.class)));
        CommandDispatcher dispatcher = new CommandDispatcher(
                registry, (definition, context) -> CommandAuthorizationDecision.allow(), event -> { });

        CommandResult result = dispatcher.dispatch("/models", new CommandContext(
                "actor-1", WORKSPACE_ID.toString(), "conversation-1"));

        assertThat(result.status()).isEqualTo(CommandResult.Status.COMPLETED);
        assertThat(result.message()).isEqualTo("模型配置状态");
        assertThat(result.data()).containsEntry("groupCount", 1).containsEntry("endpointCount", 1);
        List<Map<String, Object>> groups = castList(result.data().get("groups"));
        List<Map<String, Object>> endpoints = castList(result.data().get("endpoints"));
        assertThat(groups.getFirst()).containsEntry("groupId", groupId.toString())
                .containsEntry("displayName", "代码模型组")
                .containsEntry("taskType", "CODE")
                .containsEntry("endpointIds", List.of(endpointId.toString()));
        assertThat(endpoints.getFirst()).containsEntry("endpointId", endpointId.toString())
                .containsEntry("displayName", "代码端点")
                .containsEntry("modelId", "gpt-5.4")
                .containsEntry("capabilities", List.of("TOOL_CALLING"))
                .containsEntry("priority", 1)
                .containsEntry("weight", 3)
                .containsEntry("enabled", true)
                .doesNotContainKeys("baseUrl", "apiKey", "apiKeyMasked");
        verify(modelConfigurations).snapshot();
    }

    @Test
    void listsSupportedMultiAgentModesAndProductionAgentCatalog() {
        McpInstallationService mcpInstallations = mock(McpInstallationService.class);
        GitHubSkillInstallationService skillInstallations = mock(GitHubSkillInstallationService.class);
        ModelConfigurationService modelConfigurations = mock(ModelConfigurationService.class);
        InMemoryCommandRegistry registry = new InMemoryCommandRegistry();
        registry.replace(CapabilityCommandHandlers.definitions(
                mcpInstallations, skillInstallations, modelConfigurations,
                mock(WorkspaceAccessService.class), mock(ConversationService.class)));
        CommandDispatcher dispatcher = new CommandDispatcher(
                registry, (definition, context) -> CommandAuthorizationDecision.allow(), event -> { });

        CommandResult result = dispatcher.dispatch("/agents", new CommandContext(
                "actor-1", WORKSPACE_ID.toString(), "conversation-1"));

        assertThat(result.status()).isEqualTo(CommandResult.Status.COMPLETED);
        assertThat(result.message()).isEqualTo("多 Agent 编排能力");
        assertThat(result.data()).containsEntry("agentCount", 4)
                .containsEntry("modes", List.of(
                        "SERIAL_DEVELOPMENT", "PARALLEL_RESEARCH", "REVIEW_LOOP"));
        List<Map<String, Object>> agents = castList(result.data().get("agents"));
        assertThat(agents).anySatisfy(agent -> assertThat(agent)
                .containsEntry("agentId", "coordinator")
                .containsEntry("graphId", "multiagent-coordinator")
                .containsEntry("handoffTargets", List.of(
                        "researcher-code", "researcher-tests", "verifier")));
        assertThat(agents).anySatisfy(agent -> assertThat(agent)
                .containsEntry("agentId", "verifier")
                .containsEntry("graphId", "multiagent-verifier"));
    }

    @Test
    void showsAuthorizedCurrentWorkspaceDetails() {
        McpInstallationService mcpInstallations = mock(McpInstallationService.class);
        GitHubSkillInstallationService skillInstallations = mock(GitHubSkillInstallationService.class);
        ModelConfigurationService modelConfigurations = mock(ModelConfigurationService.class);
        WorkspaceAccessService workspaceAccess = mock(WorkspaceAccessService.class);
        when(workspaceAccess.requireWorkspace(WORKSPACE_ID, "actor-1", WorkspacePermission.VIEWER))
                .thenReturn(new WorkspaceRecord(WORKSPACE_ID, "actor-1", "产品工作区",
                        Path.of("D:/projects/agent4j"), "agent4j", WorkspacePermission.OWNER,
                        NOW, NOW));
        InMemoryCommandRegistry registry = new InMemoryCommandRegistry();
        registry.replace(CapabilityCommandHandlers.definitions(
                mcpInstallations, skillInstallations, modelConfigurations, workspaceAccess,
                mock(ConversationService.class)));
        CommandDispatcher dispatcher = new CommandDispatcher(
                registry, (definition, context) -> CommandAuthorizationDecision.allow(), event -> { });

        CommandResult result = dispatcher.dispatch("/workspace", new CommandContext(
                "actor-1", WORKSPACE_ID.toString(), "conversation-1"));

        assertThat(result.status()).isEqualTo(CommandResult.Status.COMPLETED);
        assertThat(result.message()).isEqualTo("当前工作区");
        assertThat(result.data()).containsEntry("workspaceId", WORKSPACE_ID.toString())
                .containsEntry("displayName", "产品工作区")
                .containsEntry("workspacePath", "D:/projects/agent4j")
                .containsEntry("repositoryId", "agent4j")
                .containsEntry("permission", "OWNER");
        verify(workspaceAccess).requireWorkspace(WORKSPACE_ID, "actor-1", WorkspacePermission.VIEWER);
    }

    @Test
    void showsCurrentConversationRunSummaryWithoutPromptOrErrorContent() {
        McpInstallationService mcpInstallations = mock(McpInstallationService.class);
        GitHubSkillInstallationService skillInstallations = mock(GitHubSkillInstallationService.class);
        ModelConfigurationService modelConfigurations = mock(ModelConfigurationService.class);
        WorkspaceAccessService workspaceAccess = mock(WorkspaceAccessService.class);
        ConversationService conversations = mock(ConversationService.class);
        UUID conversationId = UUID.fromString("4a0372d2-5e47-49a7-af30-ca7bdf2af8af");
        UUID runId = UUID.fromString("aa4bf863-a0e9-4d8c-b668-d98682320478");
        when(conversations.listTurns(conversationId)).thenReturn(List.of(new ConversationTurnRecord(
                UUID.fromString("e6843a43-3c25-4da5-9d64-41ced613f359"), conversationId, 2,
                "保密请求", null, runId, ConversationTurnStatus.RUNNING, null, NOW, null)));
        InMemoryCommandRegistry registry = new InMemoryCommandRegistry();
        registry.replace(CapabilityCommandHandlers.definitions(mcpInstallations, skillInstallations,
                modelConfigurations, workspaceAccess, conversations));
        CommandDispatcher dispatcher = new CommandDispatcher(
                registry, (definition, context) -> CommandAuthorizationDecision.allow(), event -> { });

        CommandResult result = dispatcher.dispatch("/runs", new CommandContext(
                "actor-1", WORKSPACE_ID.toString(), conversationId.toString()));

        assertThat(result.status()).isEqualTo(CommandResult.Status.COMPLETED);
        assertThat(result.message()).isEqualTo("当前会话 Run 状态");
        assertThat(result.data()).containsEntry("turnCount", 1)
                .containsEntry("latestRun", Map.of(
                        "turnIndex", 2L,
                        "runId", runId.toString(),
                        "status", "RUNNING",
                        "createdAt", NOW.toString()))
                .doesNotContainKeys("userContent", "assistantContent", "error");
        verify(conversations).listTurns(conversationId);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
