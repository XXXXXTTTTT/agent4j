package com.agent.web.controller;

import com.agent.web.identity.Actor;
import com.agent.web.identity.ActorResolver;
import com.agent.web.workspace.WorkspaceFileService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 工作区文件树和文本内容 REST API。 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/files")
@ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
public final class WorkspaceFileController {

    private final ActorResolver actorResolver;
    private final WorkspaceFileService fileService;

    public WorkspaceFileController(ActorResolver actorResolver, WorkspaceFileService fileService) {
        this.actorResolver = Objects.requireNonNull(actorResolver, "actorResolver 不能为空");
        this.fileService = Objects.requireNonNull(fileService, "fileService 不能为空");
    }

    @GetMapping
    public List<WorkspaceFileEntryView> list(@PathVariable UUID workspaceId,
            @RequestParam(defaultValue = "") String path) {
        Actor actor = actorResolver.current();
        return fileService.list(workspaceId, actor.userId(), path).stream()
                .map(WorkspaceFileEntryView::from).toList();
    }

    @GetMapping("/content")
    public WorkspaceFileContentView read(@PathVariable UUID workspaceId,
            @RequestParam String path) {
        Actor actor = actorResolver.current();
        return WorkspaceFileContentView.from(fileService.read(workspaceId, actor.userId(), path));
    }

    @PutMapping("/content")
    public ResponseEntity<WorkspaceFileContentView> write(@PathVariable UUID workspaceId,
            @Valid @RequestBody WorkspaceFileWriteRequest request) {
        Actor actor = actorResolver.current();
        return ResponseEntity.ok(WorkspaceFileContentView.from(fileService.write(
                workspaceId, actor.userId(), request.path(), request.content(),
                request.expectedSha256())));
    }
}
