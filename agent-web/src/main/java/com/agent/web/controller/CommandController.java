package com.agent.web.controller;

import com.agent.core.command.CommandContext;
import com.agent.core.command.CommandResult;
import com.agent.web.command.WorkspaceCommandRuntimeProvider;
import com.agent.web.identity.Actor;
import com.agent.web.identity.ActorResolver;
import com.agent.web.workspace.WorkspaceAccessService;
import com.agent.web.workspace.WorkspacePermission;
import com.agent.web.workspace.WorkspaceRecord;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 工作区 Slash Command Registry 和分发入口。 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/commands")
@ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
public final class CommandController {

    private final WorkspaceCommandRuntimeProvider runtimeProvider;
    private final WorkspaceAccessService workspaceAccessService;
    private final ActorResolver actorResolver;

    /** 创建命令控制器。 */
    public CommandController(
            WorkspaceCommandRuntimeProvider runtimeProvider,
            WorkspaceAccessService workspaceAccessService,
            ActorResolver actorResolver) {
        this.runtimeProvider = Objects.requireNonNull(runtimeProvider, "runtimeProvider 不能为空");
        this.workspaceAccessService = Objects.requireNonNull(
                workspaceAccessService, "workspaceAccessService 不能为空");
        this.actorResolver = Objects.requireNonNull(actorResolver, "actorResolver 不能为空");
    }

    /** 返回当前工作区的实时命令快照。 */
    @GetMapping
    public CommandCatalogView list(@PathVariable UUID workspaceId) {
        WorkspaceRecord workspace = requireWorkspace(workspaceId, WorkspacePermission.VIEWER);
        WorkspaceCommandRuntimeProvider.Runtime runtime = runtimeProvider.resolve(workspace);
        return new CommandCatalogView(
                runtime.registry().revision(),
                runtime.registry().list().stream().map(CommandView::from).toList());
    }

    /** 执行一次 Slash Command。 */
    @PostMapping
    public ResponseEntity<CommandResult> dispatch(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody CommandInvocationRequest request) {
        WorkspaceRecord workspace = requireWorkspace(workspaceId, WorkspacePermission.OPERATOR);
        WorkspaceCommandRuntimeProvider.Runtime runtime = runtimeProvider.resolve(workspace);
        Actor actor = actorResolver.current();
        java.util.Map<String, String> variables = new java.util.LinkedHashMap<>();
        variables.put("workspacePath", workspace.workspacePath().toString());
        if (!request.modelGroupId().isBlank()) {
            variables.put("modelGroupId", request.modelGroupId());
        }
        CommandContext context = new CommandContext(
                actor.userId(),
                workspace.workspaceId().toString(),
                request.conversationId().toString(),
                variables);
        CommandResult result = runtime.dispatcher().dispatch(request.input(), context);
        return ResponseEntity.status(status(result.status())).body(result);
    }

    private WorkspaceRecord requireWorkspace(UUID workspaceId, WorkspacePermission permission) {
        Actor actor = actorResolver.current();
        return workspaceAccessService.requireWorkspace(workspaceId, actor.userId(), permission);
    }

    private HttpStatus status(CommandResult.Status status) {
        return switch (status) {
            case COMPLETED -> HttpStatus.OK;
            case FORWARDED -> HttpStatus.ACCEPTED;
            case DENIED -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID -> HttpStatus.BAD_REQUEST;
            case FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /** Registry 目录响应。 */
    public record CommandCatalogView(long revision, List<CommandView> commands) {
        public CommandCatalogView {
            commands = List.copyOf(commands);
        }
    }
}
