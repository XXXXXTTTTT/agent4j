package com.agent.web.controller;

import com.agent.web.conversation.ConversationService;
import com.agent.web.identity.ActorResolver;
import com.agent.web.workspace.WorkspaceAccessService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 工作区与工作区下会话列表 REST API。 */
@RestController
@RequestMapping("/api/workspaces")
@ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
public final class WorkspaceController {

    private final ActorResolver actorResolver;
    private final WorkspaceAccessService workspaceAccess;
    private final ObjectProvider<ConversationService> conversationService;

    public WorkspaceController(
            ActorResolver actorResolver,
            WorkspaceAccessService workspaceAccess,
            ObjectProvider<ConversationService> conversationService) {
        this.actorResolver = Objects.requireNonNull(actorResolver, "actorResolver 不能为空");
        this.workspaceAccess = Objects.requireNonNull(workspaceAccess, "workspaceAccess 不能为空");
        this.conversationService = Objects.requireNonNull(
                conversationService, "conversationService 不能为空");
    }

    @GetMapping
    public List<WorkspaceView> list() {
        return workspaceAccess.list(actorResolver.current()).stream()
                .map(WorkspaceView::from)
                .toList();
    }

    @PostMapping
    public ResponseEntity<WorkspaceView> create(
            @Valid @RequestBody CreateWorkspaceRequest request) {
        WorkspaceView view = WorkspaceView.from(workspaceAccess.create(
                actorResolver.current(), UUID.randomUUID(), request.displayName(),
                request.workspacePath(), request.repositoryId()));
        return ResponseEntity.status(201).body(view);
    }

    @GetMapping("/{workspaceId}/conversations")
    public List<ConversationView> conversations(
            @PathVariable UUID workspaceId,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return service().listConversations(workspaceId, query, includeArchived).stream()
                .map(ConversationView::from)
                .toList();
    }

    @PostMapping("/{workspaceId}/conversations")
    public ResponseEntity<ConversationView> createConversation(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody CreateConversationRequest ignored) {
        return ResponseEntity.status(201)
                .body(ConversationView.from(service().createConversation(workspaceId)));
    }

    private ConversationService service() {
        ConversationService service = conversationService.getIfAvailable();
        if (service == null) {
            throw new IllegalStateException("持久化会话服务未启用");
        }
        return service;
    }
}
