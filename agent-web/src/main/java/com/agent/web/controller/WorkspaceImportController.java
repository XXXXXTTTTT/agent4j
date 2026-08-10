package com.agent.web.controller;

import com.agent.web.identity.ActorResolver;
import com.agent.web.workspace.WorkspaceImportService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.util.Objects;

/** 提供外部项目 ZIP 上传和工作区注册接口。 */
@RestController
@RequestMapping("/api/workspace-imports")
@ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
public final class WorkspaceImportController {

    private final ActorResolver actorResolver;
    private final WorkspaceImportService importService;

    public WorkspaceImportController(
            ActorResolver actorResolver,
            WorkspaceImportService importService) {
        this.actorResolver = Objects.requireNonNull(actorResolver, "actorResolver 不能为空");
        this.importService = Objects.requireNonNull(importService, "importService 不能为空");
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<WorkspaceView>> importArchive(
            @RequestPart("displayName") String displayName,
            @RequestPart("repositoryId") String repositoryId,
            @RequestPart("archive") FilePart archive) {
        Path uploadFile = importService.createUploadFile();
        return archive.transferTo(uploadFile)
                .then(Mono.fromCallable(() -> ResponseEntity.status(201).body(
                        WorkspaceView.from(importService.importArchive(
                                actorResolver.current(), displayName, repositoryId, uploadFile))))
                        .subscribeOn(Schedulers.boundedElastic()))
                .doFinally(ignored -> importService.discardUploadFile(uploadFile));
    }
}
