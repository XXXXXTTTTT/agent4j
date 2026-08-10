package com.agent.web.controller;

import com.agent.web.identity.Actor;
import com.agent.web.identity.ActorResolver;
import com.agent.web.workspace.WorkspaceImportService;
import com.agent.web.workspace.WorkspacePermission;
import com.agent.web.workspace.WorkspaceRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@WebFluxTest(
        controllers = WorkspaceImportController.class,
        properties = "agent.production.enabled=true")
@Import(RunExceptionHandler.class)
class WorkspaceImportControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private ActorResolver actorResolver;

    @MockBean
    private WorkspaceImportService importService;

    @TempDir
    Path root;

    private Path upload;

    @BeforeEach
    void setUp() throws Exception {
        upload = Files.createTempFile(root, "upload-", ".zip");
        when(actorResolver.current()).thenReturn(new Actor("local", "本地用户"));
        when(importService.createUploadFile()).thenReturn(upload);
    }

    @Test
    void importsMultipartArchiveAndReturnsWorkspace() {
        UUID workspaceId = UUID.randomUUID();
        WorkspaceRecord workspace = new WorkspaceRecord(
                workspaceId, "local", "demo", root.resolve("demo"), "repo",
                WorkspacePermission.OWNER, Instant.parse("2026-08-10T00:00:00Z"),
                Instant.parse("2026-08-10T00:00:00Z"));
        when(importService.importArchive(any(), eq("demo"), eq("repo"), eq(upload)))
                .thenReturn(workspace);

        webTestClient.post().uri("/api/workspace-imports")
                .body(BodyInserters.fromMultipartData(form("demo", "repo").build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.workspaceId").isEqualTo(workspaceId.toString())
                .jsonPath("$.displayName").isEqualTo("demo")
                .jsonPath("$.repositoryId").isEqualTo("repo");
    }

    @Test
    void mapsImportLimitToPayloadTooLarge() {
        when(importService.importArchive(any(), eq("demo"), eq("repo"), eq(upload)))
                .thenThrow(new WorkspaceImportService.ImportLimitExceededException("ZIP 文件超过大小上限"));

        webTestClient.post().uri("/api/workspace-imports")
                .body(BodyInserters.fromMultipartData(form("demo", "repo").build()))
                .exchange()
                .expectStatus().isEqualTo(413)
                .expectBody().jsonPath("$.detail").isEqualTo("ZIP 文件超过大小上限");
    }

    @Test
    void mapsImportConflictToConflict() {
        when(importService.importArchive(any(), eq("demo"), eq("repo"), eq(upload)))
                .thenThrow(new WorkspaceImportService.ImportConflictException("工作区导入目标已存在"));

        webTestClient.post().uri("/api/workspace-imports")
                .body(BodyInserters.fromMultipartData(form("demo", "repo").build()))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.detail").isEqualTo("工作区导入目标已存在");
    }

    private MultipartBodyBuilder form(String displayName, String repositoryId) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("displayName", displayName);
        body.part("repositoryId", repositoryId);
        body.part("archive", new ByteArrayResource(new byte[] {80, 75, 3, 4}) {
            @Override
            public String getFilename() {
                return "project.zip";
            }
        });
        return body;
    }
}
