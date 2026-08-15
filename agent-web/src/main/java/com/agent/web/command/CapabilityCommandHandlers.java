package com.agent.web.command;

import com.agent.core.command.CommandChannel;
import com.agent.core.command.CommandContext;
import com.agent.core.command.CommandDefinition;
import com.agent.core.command.CommandPermission;
import com.agent.core.command.CommandResult;
import com.agent.core.command.CommandSource;
import com.agent.web.mcp.installation.McpInstallationDetails;
import com.agent.web.mcp.installation.McpInstallationRecord;
import com.agent.web.mcp.installation.McpInstallationService;
import com.agent.web.skill.GitHubSkillInstallationService;
import com.agent.web.skill.SkillInstallationRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 将已安装能力以本地 Slash Command 的形式暴露给当前工作区。 */
public final class CapabilityCommandHandlers {

    private CapabilityCommandHandlers() {
    }

    /** 创建 MCP 与 Skill 安装状态查询命令。 */
    public static List<CommandDefinition> definitions(
            McpInstallationService mcpInstallations,
            GitHubSkillInstallationService skillInstallations) {
        Objects.requireNonNull(mcpInstallations, "mcpInstallations 不能为空");
        Objects.requireNonNull(skillInstallations, "skillInstallations 不能为空");
        return List.of(
                definition("mcp", "MCP", "显示当前工作区的 MCP 安装状态",
                        context -> mcpResult(context, mcpInstallations)),
                definition("skills", "Skills", "显示当前工作区的 Skill 安装状态",
                        context -> skillResult(context, skillInstallations)));
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
}
