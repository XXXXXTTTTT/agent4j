package com.agent.web.controller;

import com.agent.web.identity.Actor;
import com.agent.web.identity.ActorResolver;
import com.agent.web.workspace.WorkspaceDirectoryBrowser;
import com.agent.web.workspace.WorkspaceDirectoryListing;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@WebFluxTest(
        controllers = WorkspaceDirectoryController.class,
        properties = "agent.production.enabled=true")
@Import(RunExceptionHandler.class)
class WorkspaceDirectoryControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private ActorResolver actorResolver;

    @MockBean
    private WorkspaceDirectoryBrowser browser;

    @Test
    void returnsExactDirectoryView() {
        Path root = Path.of("/agent-workspace");
        Path project = root.resolve("project");
        when(actorResolver.current()).thenReturn(new Actor("local", "本地用户"));
        when(browser.browseRoot()).thenReturn(root);
        when(browser.browse(root)).thenReturn(new WorkspaceDirectoryListing(root, null, List.of(project)));

        webTestClient.get().uri("/api/workspace-directories")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.currentPath").isEqualTo("/agent-workspace")
                .jsonPath("$.parentPath").doesNotExist()
                .jsonPath("$.entries[0].name").isEqualTo("project")
                .jsonPath("$.entries[0].path").isEqualTo("/agent-workspace/project");
    }

    @Test
    void mapsDirectoryValidationFailureToBadRequest() {
        when(actorResolver.current()).thenReturn(new Actor("local", "本地用户"));
        when(browser.browse(any(Path.class)))
                .thenThrow(new IllegalArgumentException("workspacePath 必须位于配置工作区内"));

        webTestClient.get().uri(uriBuilder -> uriBuilder
                        .path("/api/workspace-directories")
                        .queryParam("path", "/outside")
                        .build())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.detail")
                .isEqualTo("workspacePath 必须位于配置工作区内");
    }
}
