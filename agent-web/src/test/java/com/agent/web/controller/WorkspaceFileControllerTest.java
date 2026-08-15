package com.agent.web.controller;

import com.agent.web.identity.Actor;
import com.agent.web.identity.ActorResolver;
import com.agent.web.workspace.WorkspaceFileContent;
import com.agent.web.workspace.WorkspaceFileEntry;
import com.agent.web.workspace.WorkspaceFileService;
import com.agent.web.workspace.WorkspaceProjectService;
import com.agent.web.workspace.WorkspaceRecord;
import com.agent.web.workspace.WorkspacePermission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@WebFluxTest(
        controllers = {WorkspaceProjectController.class, WorkspaceFileController.class},
        properties = "agent.production.enabled=true")
@Import(RunExceptionHandler.class)
class WorkspaceFileControllerTest {

    private static final UUID WORKSPACE_ID =
            UUID.fromString("f4c2a1bb-0f6d-4df2-89db-0b31e20e4c0e");
    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private ActorResolver actorResolver;

    @MockBean
    private WorkspaceProjectService projectService;

    @MockBean
    private WorkspaceFileService fileService;

    @Test
    void createsProjectAndReturnsWorkspaceView() {
        when(actorResolver.current()).thenReturn(new Actor("local", "本地用户"));
        when(projectService.create(any(), eq("Demo"), eq("demo"), eq("repo")))
                .thenReturn(workspace());

        webTestClient.post().uri("/api/workspaces/projects")
                .bodyValue(new CreateProjectRequest("Demo", "demo", "repo"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.workspaceId").isEqualTo(WORKSPACE_ID.toString())
                .jsonPath("$.displayName").isEqualTo("Demo")
                .jsonPath("$.workspacePath").isEqualTo("/agent-workspace/demo");
    }

    @Test
    void listsAndReadsFilesWithWorkspaceRelativePath() {
        when(actorResolver.current()).thenReturn(new Actor("local", "本地用户"));
        when(fileService.list(WORKSPACE_ID, "local", "src"))
                .thenReturn(List.of(new WorkspaceFileEntry(
                        "Main.java", "src/Main.java", WorkspaceFileEntry.Kind.FILE, 12, NOW)));
        when(fileService.read(WORKSPACE_ID, "local", "src/Main.java"))
                .thenReturn(new WorkspaceFileContent("src/Main.java", "class Main {}", "sha", NOW));

        webTestClient.get().uri("/api/workspaces/{id}/files?path=src", WORKSPACE_ID)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$[0].path").isEqualTo("src/Main.java")
                .jsonPath("$[0].kind").isEqualTo("FILE");
        webTestClient.get().uri("/api/workspaces/{id}/files/content?path=src/Main.java", WORKSPACE_ID)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.content").isEqualTo("class Main {}")
                .jsonPath("$.sha256").isEqualTo("sha");
    }

    @Test
    void writesFileAndMapsShaConflict() {
        when(actorResolver.current()).thenReturn(new Actor("local", "本地用户"));
        when(fileService.write(WORKSPACE_ID, "local", "Main.java", "new", "old"))
                .thenThrow(new WorkspaceFileService.FileConflictException("actual"));

        webTestClient.put().uri("/api/workspaces/{id}/files/content", WORKSPACE_ID)
                .bodyValue(new WorkspaceFileWriteRequest("Main.java", "new", "old"))
                .exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.detail").isEqualTo("文件已被其他操作修改");
    }

    private static WorkspaceRecord workspace() {
        return new WorkspaceRecord(WORKSPACE_ID, "local", "Demo", Path.of("/agent-workspace/demo"),
                "repo", WorkspacePermission.OWNER, NOW, NOW);
    }
}
