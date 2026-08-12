package com.agent.web.controller;

import com.agent.core.cli.CliAuthorizationContext;
import com.agent.core.cli.CliCommandCatalog;
import com.agent.core.cli.CliCommandDefinition;
import com.agent.core.cli.CliCommandIntent;
import com.agent.core.cli.WorkspaceTerminalTargetResolver;
import com.agent.core.engine.AgentRunService;
import com.agent.core.engine.AgentState;
import com.agent.core.intent.RequiredCapability;
import com.agent.core.nodes.CoderNode;
import com.agent.core.nodes.OpsNode;
import com.agent.core.nodes.PlannerNode;
import com.agent.web.identity.Actor;
import com.agent.web.identity.ActorResolver;
import com.agent.web.workspace.WorkspaceAccessService;
import com.agent.web.workspace.WorkspacePermission;
import com.agent.web.workspace.WorkspaceRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 提供工作区隔离的受治理 CLI 命令目录和 Run 创建入口。 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/cli")
public final class CliCommandController {

    private static final String GRAPH_ID = "governed-cli";

    private final AgentRunService runService;
    private final CliCommandCatalog commandCatalog;
    private final WorkspaceAccessService workspaceAccessService;
    private final ActorResolver actorResolver;
    private final WorkspaceTerminalTargetResolver workspaceTargetResolver;
    private final ObjectMapper objectMapper;

    /** 创建 CLI 工作台控制器。 */
    public CliCommandController(
            AgentRunService runService,
            CliCommandCatalog commandCatalog,
            WorkspaceAccessService workspaceAccessService,
            ActorResolver actorResolver,
            WorkspaceTerminalTargetResolver workspaceTargetResolver,
            ObjectMapper objectMapper) {
        this.runService = Objects.requireNonNull(runService, "runService 不能为空");
        this.commandCatalog = Objects.requireNonNull(commandCatalog, "commandCatalog 不能为空");
        this.workspaceAccessService = Objects.requireNonNull(
                workspaceAccessService, "workspaceAccessService 不能为空");
        this.actorResolver = Objects.requireNonNull(actorResolver, "actorResolver 不能为空");
        this.workspaceTargetResolver = Objects.requireNonNull(
                workspaceTargetResolver, "workspaceTargetResolver 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /** 返回当前工作区可执行的受治理命令。 */
    @GetMapping("/commands")
    public List<CliCommandView> list(@PathVariable UUID workspaceId) {
        requireWorkspace(workspaceId);
        return commandCatalog.list().stream().map(CliCommandView::from).toList();
    }

    /** 创建专用 `governed-cli` Run，不接受任意 Shell 文本或审批覆盖。 */
    @PostMapping("/runs")
    public ResponseEntity<RunView> start(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody CliRunRequest request) {
        WorkspaceRecord workspace = requireWorkspace(workspaceId);
        CliCommandDefinition definition = commandCatalog.find(request.commandName())
                .orElseThrow(() -> new com.agent.core.cli.CliCommandNotFoundException(
                        request.commandName()));
        CliCommandIntent intent = new CliCommandIntent(
                request.commandName(),
                request.arguments(),
                workspace.workspacePath(),
                workspaceTargetResolver.resolve(workspace.workspacePath()),
                Duration.ofSeconds(request.timeoutSeconds()));
        commandCatalog.authorize(intent, new CliAuthorizationContext(
                definition.requiredCapabilities(), false, false));

        AgentState state = AgentState.empty()
                .withVariable(OpsNode.COMMAND_NAME_KEY, definition.name())
                .withVariable(OpsNode.COMMAND_ARGUMENTS_KEY, argumentsJson(request.arguments()))
                .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.workspacePath().toString())
                .withVariable(PlannerNode.REQUIRED_CAPABILITIES_KEY,
                        capabilityNames(definition.requiredCapabilities()));
        return ResponseEntity.accepted().body(RunView.from(runService.start(GRAPH_ID, state)));
    }

    private WorkspaceRecord requireWorkspace(UUID workspaceId) {
        Actor actor = actorResolver.current();
        return workspaceAccessService.requireWorkspace(
                workspaceId, actor.userId(), WorkspacePermission.OPERATOR);
    }

    private String argumentsJson(List<String> arguments) {
        try {
            return objectMapper.writeValueAsString(arguments);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("CLI 参数无法序列化", exception);
        }
    }

    private String capabilityNames(Set<RequiredCapability> capabilities) {
        return capabilities.stream()
                .sorted(Comparator.comparingInt(Enum::ordinal))
                .map(Enum::name)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }
}
