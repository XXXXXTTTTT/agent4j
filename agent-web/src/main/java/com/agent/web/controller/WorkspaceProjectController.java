package com.agent.web.controller;

import com.agent.web.identity.ActorResolver;
import com.agent.web.workspace.WorkspaceProjectService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** 创建并注册空项目工作区。 */
@RestController
@RequestMapping("/api/workspaces/projects")
@ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
public final class WorkspaceProjectController {

    private final ActorResolver actorResolver;
    private final WorkspaceProjectService projectService;

    public WorkspaceProjectController(ActorResolver actorResolver,
            WorkspaceProjectService projectService) {
        this.actorResolver = Objects.requireNonNull(actorResolver, "actorResolver 不能为空");
        this.projectService = Objects.requireNonNull(projectService, "projectService 不能为空");
    }

    @PostMapping
    public ResponseEntity<WorkspaceView> create(@Valid @RequestBody CreateProjectRequest request) {
        return ResponseEntity.status(201).body(WorkspaceView.from(projectService.create(
                actorResolver.current(), request.displayName(), request.directoryName(),
                request.repositoryId())));
    }
}
