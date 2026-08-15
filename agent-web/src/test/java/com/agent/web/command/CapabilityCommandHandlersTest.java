package com.agent.web.command;

import com.agent.core.command.CommandAuthorizationDecision;
import com.agent.core.command.CommandContext;
import com.agent.core.command.CommandDispatcher;
import com.agent.core.command.CommandResult;
import com.agent.core.command.InMemoryCommandRegistry;
import com.agent.web.capability.InstallationScope;
import com.agent.web.mcp.installation.McpInstallationDetails;
import com.agent.web.mcp.installation.McpInstallationRecord;
import com.agent.web.mcp.installation.McpInstallationService;
import com.agent.web.skill.GitHubSkillInstallationService;
import com.agent.web.skill.SkillInstallationRecord;
import com.agent.web.skill.SkillInstallationStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
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
        registry.replace(CapabilityCommandHandlers.definitions(mcpInstallations, skillInstallations));
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
}
