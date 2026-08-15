package com.agent.web.command;

import com.agent.core.command.CommandChannel;
import com.agent.core.command.CommandContext;
import com.agent.core.command.CommandDefinition;
import com.agent.core.command.CommandPermission;
import com.agent.core.command.CommandResult;
import com.agent.core.command.CommandSource;
import com.agent.core.multiagent.AgentDescriptor;
import com.agent.core.orchestration.OrchestrationMode;
import com.agent.web.mcp.installation.McpInstallationDetails;
import com.agent.web.mcp.installation.McpInstallationRecord;
import com.agent.web.mcp.installation.McpInstallationService;
import com.agent.web.conversation.ConversationService;
import com.agent.web.conversation.ConversationTurnRecord;
import com.agent.web.model.ModelConfigurationService;
import com.agent.web.model.ModelConfigurationSnapshot;
import com.agent.web.model.ModelEndpointRecord;
import com.agent.web.model.ModelGroupRecord;
import com.agent.web.orchestration.ProductionMultiAgentOrchestrator;
import com.agent.web.skill.GitHubSkillInstallationService;
import com.agent.web.skill.SkillInstallationRecord;
import com.agent.web.workspace.WorkspaceAccessService;
import com.agent.web.workspace.WorkspacePermission;
import com.agent.web.workspace.WorkspaceRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 将已安装能力以本地 Slash Command 的形式暴露给当前工作区。 */
public final class CapabilityCommandHandlers {

    private CapabilityCommandHandlers() {
    }

    /** 创建 MCP、Skill 与模型配置状态查询命令。 */
    public static List<CommandDefinition> definitions(
            McpInstallationService mcpInstallations,
            GitHubSkillInstallationService skillInstallations,
            ModelConfigurationService modelConfigurations,
            WorkspaceAccessService workspaceAccess,
            ConversationService conversations) {
        Objects.requireNonNull(mcpInstallations, "mcpInstallations 不能为空");
        Objects.requireNonNull(skillInstallations, "skillInstallations 不能为空");
        Objects.requireNonNull(modelConfigurations, "modelConfigurations 不能为空");
        Objects.requireNonNull(workspaceAccess, "workspaceAccess 不能为空");
        Objects.requireNonNull(conversations, "conversations 不能为空");
        return List.of(
                definition("mcp", "MCP", "显示当前工作区的 MCP 安装状态",
                        context -> mcpResult(context, mcpInstallations)),
                definition("skills", "Skills", "显示当前工作区的 Skill 安装状态",
                        context -> skillResult(context, skillInstallations)),
                definition("models", "Models", "显示当前用户的模型组和端点状态",
                        context -> modelResult(modelConfigurations)),
                definition("agents", "Agents", "显示已注册的多 Agent 编排能力",
                        context -> agentsResult()),
                definition("workspace", "Workspace", "显示当前工作区的名称、路径与权限",
                        context -> workspaceResult(context, workspaceAccess)),
                definition("runs", "Runs", "显示当前会话的最新 Run 状态",
                        context -> runsResult(context, conversations)),
                definition("doctor", "Doctor", "检查当前工作区的本地 Agent 能力",
                        context -> doctorResult(context, mcpInstallations, skillInstallations,
                                modelConfigurations, workspaceAccess, conversations)));
    }

    private static CommandDefinition definition(
            String name,
            String displayName,
            String description,
            java.util.function.Function<CommandContext, CommandResult> handler) {
        return new CommandDefinition(
                name, displayName, description, List.of(), List.of(),
                CommandChannel.SYSTEM_DIRECTIVE, CommandSource.BUILT_IN, CommandPermission.VIEWER,
                (invocation, context) -> handler.apply(context));
    }

    private static CommandResult mcpResult(CommandContext context, McpInstallationService service) {
        UUID workspaceId = UUID.fromString(context.workspaceId());
        List<Map<String, Object>> installations = service.listDetails(workspaceId).stream()
                .map(CapabilityCommandHandlers::mcpView)
                .toList();
        return new CommandResult(CommandResult.Status.COMPLETED, null, "MCP 安装状态", Map.of(
                "count", installations.size(),
                "installations", installations));
    }

    private static Map<String, Object> mcpView(McpInstallationDetails details) {
        McpInstallationRecord installation = details.installation();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("installationId", installation.installationId().toString());
        view.put("snapshotId", installation.snapshotId().toString());
        view.put("scope", installation.scope().name());
        view.put("status", installation.status().name());
        view.put("riskLevel", installation.riskLevel().name());
        view.put("requiredCapabilities", installation.requiredCapabilities().stream()
                .map(Enum::name).sorted().toList());
        view.put("workspaceMountMode", installation.workspaceMountMode().name());
        view.put("networkMode", installation.networkMode().name());
        view.put("runtimeImageConfirmed", installation.runtimeImageConfirmed());
        view.put("version", installation.version());
        view.put("environmentVariableNames", details.environmentVariableNames());
        if (installation.runtimeWorkspaceId() != null) {
            view.put("runtimeWorkspaceId", installation.runtimeWorkspaceId().toString());
        }
        return Map.copyOf(view);
    }

    private static CommandResult skillResult(CommandContext context, GitHubSkillInstallationService service) {
        UUID workspaceId = UUID.fromString(context.workspaceId());
        List<Map<String, Object>> installations = service.list(workspaceId).stream()
                .map(CapabilityCommandHandlers::skillView)
                .toList();
        return new CommandResult(CommandResult.Status.COMPLETED, null, "Skill 安装状态", Map.of(
                "count", installations.size(),
                "installations", installations));
    }

    private static Map<String, Object> skillView(SkillInstallationRecord installation) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("skillInstallationId", installation.skillInstallationId().toString());
        view.put("skillSnapshotId", installation.skillSnapshotId().toString());
        view.put("scope", installation.scope().name());
        view.put("status", installation.status().name());
        view.put("version", installation.version());
        return Map.copyOf(view);
    }

    private static CommandResult modelResult(ModelConfigurationService service) {
        ModelConfigurationSnapshot snapshot = service.snapshot();
        List<Map<String, Object>> groups = snapshot.groups().stream()
                .map(CapabilityCommandHandlers::modelGroupView)
                .toList();
        List<Map<String, Object>> endpoints = snapshot.endpoints().stream()
                .map(CapabilityCommandHandlers::modelEndpointView)
                .toList();
        return new CommandResult(CommandResult.Status.COMPLETED, null, "模型配置状态", Map.of(
                "groupCount", groups.size(),
                "endpointCount", endpoints.size(),
                "groups", groups,
                "endpoints", endpoints));
    }

    private static Map<String, Object> modelGroupView(ModelGroupRecord group) {
        return Map.of(
                "groupId", group.groupId().toString(),
                "displayName", group.displayName(),
                "taskType", group.taskType().name(),
                "endpointIds", group.endpointIds().stream().map(UUID::toString).toList());
    }

    private static Map<String, Object> modelEndpointView(ModelEndpointRecord endpoint) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("endpointId", endpoint.endpointId().toString());
        view.put("displayName", endpoint.displayName());
        view.put("modelId", endpoint.modelId());
        view.put("capabilities", endpoint.capabilities().stream().map(Enum::name).sorted().toList());
        view.put("priority", endpoint.priority());
        view.put("weight", endpoint.weight());
        view.put("enabled", endpoint.enabled());
        return Map.copyOf(view);
    }

    private static CommandResult agentsResult() {
        List<Map<String, Object>> agents = ProductionMultiAgentOrchestrator.catalog().list().stream()
                .map(CapabilityCommandHandlers::agentView)
                .toList();
        return new CommandResult(CommandResult.Status.COMPLETED, null, "多 Agent 编排能力", Map.of(
                "agentCount", agents.size(),
                "modes", java.util.Arrays.stream(OrchestrationMode.values()).map(Enum::name).toList(),
                "agents", agents));
    }

    private static Map<String, Object> agentView(AgentDescriptor descriptor) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("agentId", descriptor.agentId());
        view.put("graphId", descriptor.graphId());
        view.put("readableStateKeys", descriptor.readableStateKeys().stream().sorted().toList());
        view.put("ownedStateKeys", descriptor.ownedStateKeys().stream().sorted().toList());
        view.put("handoffTargets", descriptor.handoffTargets().stream().sorted().toList());
        return Map.copyOf(view);
    }

    private static CommandResult workspaceResult(
            CommandContext context, WorkspaceAccessService workspaceAccess) {
        WorkspaceRecord workspace = workspaceAccess.requireWorkspace(
                UUID.fromString(context.workspaceId()), context.actorId(), WorkspacePermission.VIEWER);
        String separator = workspace.workspacePath().getFileSystem().getSeparator();
        return new CommandResult(CommandResult.Status.COMPLETED, null, "当前工作区", Map.of(
                "workspaceId", workspace.workspaceId().toString(),
                "displayName", workspace.displayName(),
                "workspacePath", workspace.workspacePath().toString().replace(separator, "/"),
                "repositoryId", workspace.repositoryId(),
                "permission", workspace.permission().name()));
    }

    private static CommandResult runsResult(CommandContext context, ConversationService conversations) {
        List<ConversationTurnRecord> turns = conversations.listTurns(
                UUID.fromString(context.conversationId()));
        Map<String, Object> latestRun = turns.stream()
                .max(java.util.Comparator.comparingLong(ConversationTurnRecord::turnIndex))
                .map(CapabilityCommandHandlers::runView)
                .orElseGet(Map::of);
        return new CommandResult(CommandResult.Status.COMPLETED, null, "当前会话 Run 状态", Map.of(
                "turnCount", turns.size(),
                "latestRun", latestRun));
    }

    private static Map<String, Object> runView(ConversationTurnRecord turn) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("turnIndex", turn.turnIndex());
        view.put("status", turn.status().name());
        view.put("createdAt", turn.createdAt().toString());
        if (turn.runId() != null) {
            view.put("runId", turn.runId().toString());
        }
        return Map.copyOf(view);
    }

    private static CommandResult doctorResult(
            CommandContext context,
            McpInstallationService mcpInstallations,
            GitHubSkillInstallationService skillInstallations,
            ModelConfigurationService modelConfigurations,
            WorkspaceAccessService workspaceAccess,
            ConversationService conversations) {
        UUID workspaceId = UUID.fromString(context.workspaceId());
        UUID conversationId = UUID.fromString(context.conversationId());
        workspaceAccess.requireWorkspace(workspaceId, context.actorId(), WorkspacePermission.VIEWER);
        var modelSnapshot = modelConfigurations.snapshot();
        int mcpCount = mcpInstallations.listDetails(workspaceId).size();
        int skillCount = skillInstallations.list(workspaceId).size();
        int turnCount = conversations.listTurns(conversationId).size();
        return new CommandResult(CommandResult.Status.COMPLETED, null, "环境诊断", Map.of(
                "status", "READY",
                "workspaceAccessible", true,
                "modelGroupCount", modelSnapshot.groups().size(),
                "modelEndpointCount", modelSnapshot.endpoints().size(),
                "mcpInstallationCount", mcpCount,
                "skillInstallationCount", skillCount,
                "conversationTurnCount", turnCount));
    }
}
