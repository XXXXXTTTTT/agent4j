package com.agent.web.controller;

import com.agent.web.capability.InstallationScope;
import com.agent.web.mcp.catalog.OfficialMcpCatalogClient;
import com.agent.web.mcp.catalog.OfficialMcpServerRecord;
import com.agent.web.mcp.installation.McpInstallationPreview;
import com.agent.web.mcp.installation.McpInstallationRecord;
import com.agent.web.mcp.installation.McpInstallationService;
import com.agent.web.mcp.installation.McpInstallationStatus;
import com.agent.web.skill.GitHubSkillCatalogClient;
import com.agent.web.skill.GitHubSkillInstallationService;
import com.agent.web.skill.GitHubSkillRepository;
import com.agent.web.skill.SkillInstallationPreview;
import com.agent.web.skill.SkillInstallationRecord;
import com.agent.web.skill.SkillInstallationStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest(controllers = CapabilityManagementController.class,
        properties = "agent.production.enabled=true")
@Import(RunExceptionHandler.class)
class CapabilityManagementControllerTest {
    private static final UUID WORKSPACE_ID = UUID.fromString("06a6ace4-f5ff-4cab-a2d8-becb50678e95");
    private static final UUID PREVIEW_ID = UUID.fromString("ea8145b8-5594-41f9-a8f6-9d561cdd857a");
    private static final UUID INSTALLATION_ID = UUID.fromString("af48f7d1-7ad0-4c9f-a9d5-87afad944b2e");
    private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");

    @Autowired
    private WebTestClient client;

    @MockBean
    private OfficialMcpCatalogClient mcpCatalog;

    @MockBean
    private McpInstallationService mcpInstallations;

    @MockBean
    private GitHubSkillCatalogClient skillCatalog;

    @MockBean
    private GitHubSkillInstallationService skillInstallations;

    @Test
    void returnsOfficialMcpCatalogAndCreatesWorkspacePreview() {
        OfficialMcpServerRecord server = server();
        when(mcpCatalog.fetchCatalogResult()).thenReturn(new OfficialMcpCatalogClient.CatalogResult(
                "modelcontextprotocol/servers", "76d64c822f5125032f89eb71dbdb94e42b434821", NOW,
                NOW.plusSeconds(300), "etag", "FRESH", List.of(server), Map.of()));
        when(mcpInstallations.preview(eq(WORKSPACE_ID), eq(server), eq(InstallationScope.WORKSPACE), eq(WORKSPACE_ID)))
                .thenReturn(mcpPreview());

        client.get().uri("/api/mcp/catalog")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.repository").isEqualTo("modelcontextprotocol/servers")
                .jsonPath("$.servers[0].serviceId").isEqualTo("everything")
                .jsonPath("$.servers[0].command").isEqualTo("npx")
                .jsonPath("$.servers[0].environmentVariableNames[0]").isEqualTo("MCP_TOKEN");

        client.post().uri("/api/workspaces/{workspaceId}/mcp/installations/preview", WORKSPACE_ID)
                .header("Content-Type", "application/json")
                .bodyValue("""
                        {"serverKey":"everything","scope":"WORKSPACE","targetWorkspaceId":"%s"}
                        """.formatted(WORKSPACE_ID))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.previewId").isEqualTo(PREVIEW_ID.toString())
                .jsonPath("$.confirmationToken").isEqualTo("confirm-mcp")
                .jsonPath("$.sideEffectFree").isEqualTo(true);
    }

    @Test
    void refreshesOfficialMcpCatalogWithoutUsingCachedCatalogMethod() {
        when(mcpCatalog.refreshCatalogResult()).thenReturn(new OfficialMcpCatalogClient.CatalogResult(
                "modelcontextprotocol/servers", "76d64c822f5125032f89eb71dbdb94e42b434821", NOW,
                NOW.plusSeconds(300), "etag-refresh", "FRESH", List.of(server()), Map.of()));

        client.post().uri("/api/mcp/catalog/refresh")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.etag").isEqualTo("etag-refresh")
                .jsonPath("$.servers[0].serviceId").isEqualTo("everything");

        verify(mcpCatalog).refreshCatalogResult();
    }

    @Test
    void mapsCapabilityInstallationErrorsToStableClientResponses() {
        when(mcpInstallations.confirm(eq(WORKSPACE_ID), eq(PREVIEW_ID), eq("expired"),
                eq(InstallationScope.WORKSPACE), eq(WORKSPACE_ID)))
                .thenThrow(new McpInstallationService.InvalidConfirmationException());
        when(skillInstallations.uninstall(WORKSPACE_ID, INSTALLATION_ID))
                .thenThrow(new GitHubSkillInstallationService.InstallationNotFoundException(INSTALLATION_ID));

        client.post().uri("/api/workspaces/{workspaceId}/mcp/installations", WORKSPACE_ID)
                .header("Content-Type", "application/json")
                .bodyValue("""
                        {"previewId":"%s","confirmationToken":"expired","scope":"WORKSPACE","targetWorkspaceId":"%s"}
                        """.formatted(PREVIEW_ID, WORKSPACE_ID))
                .exchange().expectStatus().isBadRequest().expectBody()
                .jsonPath("$.detail").isEqualTo("MCP 安装确认无效或已过期");

        client.delete().uri("/api/workspaces/{workspaceId}/skills/{installationId}", WORKSPACE_ID, INSTALLATION_ID)
                .exchange().expectStatus().isNotFound().expectBody()
                .jsonPath("$.detail").isEqualTo("Skill 安装不存在或当前用户无权访问: " + INSTALLATION_ID);
    }

    @Test
    void confirmsListsAndUninstallsMcpWithoutExposingTokenDigest() {
        McpInstallationRecord installation = mcpInstallation();
        when(mcpInstallations.confirm(eq(WORKSPACE_ID), eq(PREVIEW_ID), eq("confirm-mcp"),
                eq(InstallationScope.WORKSPACE), eq(WORKSPACE_ID))).thenReturn(installation);
        when(mcpInstallations.list(WORKSPACE_ID)).thenReturn(List.of(installation));
        when(mcpInstallations.uninstall(WORKSPACE_ID, INSTALLATION_ID)).thenReturn(installation);

        client.post().uri("/api/workspaces/{workspaceId}/mcp/installations", WORKSPACE_ID)
                .header("Content-Type", "application/json")
                .bodyValue("""
                        {"previewId":"%s","confirmationToken":"confirm-mcp","scope":"WORKSPACE","targetWorkspaceId":"%s"}
                        """.formatted(PREVIEW_ID, WORKSPACE_ID))
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.installationId").isEqualTo(INSTALLATION_ID.toString())
                .jsonPath("$.confirmationTokenSha256").doesNotExist();

        client.get().uri("/api/workspaces/{workspaceId}/mcp/installations", WORKSPACE_ID)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$[0].status").isEqualTo("STOPPED")
                .jsonPath("$[0].confirmationTokenSha256").doesNotExist();

        client.delete().uri(uriBuilder -> uriBuilder
                        .path("/api/workspaces/{workspaceId}/mcp/installations/{installationId}")
                        .queryParam("expectedVersion", installation.version())
                        .build(WORKSPACE_ID, INSTALLATION_ID))
                .exchange().expectStatus().isOk();
        verify(mcpInstallations).uninstall(WORKSPACE_ID, INSTALLATION_ID);
    }

    @Test
    void searchesPreviewsConfirmsListsAndUninstallsGitHubSkill() {
        when(skillCatalog.search("java review")).thenReturn(List.of(new GitHubSkillRepository(
                "octo/java-review", URI.create("https://github.com/octo/java-review"),
                "main", "审查 Java", "MIT")));
        when(skillInstallations.preview(eq(WORKSPACE_ID), eq("octo/java-review"),
                eq(InstallationScope.USER_GLOBAL), eq(null))).thenReturn(skillPreview());
        when(skillInstallations.confirm(eq(WORKSPACE_ID), eq(PREVIEW_ID), eq("confirm-skill"),
                eq(InstallationScope.USER_GLOBAL), eq(null))).thenReturn(skillInstallation());
        when(skillInstallations.list(WORKSPACE_ID)).thenReturn(List.of(skillInstallation()));
        when(skillInstallations.uninstall(WORKSPACE_ID, INSTALLATION_ID)).thenReturn(skillInstallation());

        client.get().uri("/api/skills/search?q=java+review")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$[0].repository").isEqualTo("octo/java-review")
                .jsonPath("$[0].license").isEqualTo("MIT");

        client.post().uri("/api/workspaces/{workspaceId}/skills/preview", WORKSPACE_ID)
                .header("Content-Type", "application/json")
                .bodyValue("""
                        {"repository":"octo/java-review","scope":"USER_GLOBAL"}
                        """)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.scope").isEqualTo("USER_GLOBAL")
                .jsonPath("$.confirmationToken").isEqualTo("confirm-skill");

        client.post().uri("/api/workspaces/{workspaceId}/skills", WORKSPACE_ID)
                .header("Content-Type", "application/json")
                .bodyValue("""
                        {"previewId":"%s","confirmationToken":"confirm-skill","scope":"USER_GLOBAL"}
                        """.formatted(PREVIEW_ID))
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.skillInstallationId").isEqualTo(INSTALLATION_ID.toString())
                .jsonPath("$.confirmationTokenSha256").doesNotExist();

        client.get().uri("/api/workspaces/{workspaceId}/skills", WORKSPACE_ID)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$[0].status").isEqualTo("APPROVED")
                .jsonPath("$[0].confirmationTokenSha256").doesNotExist();

        client.delete().uri("/api/workspaces/{workspaceId}/skills/{installationId}",
                        WORKSPACE_ID, INSTALLATION_ID)
                .exchange().expectStatus().isOk();
        verify(skillInstallations).uninstall(WORKSPACE_ID, INSTALLATION_ID);
    }

    private static OfficialMcpServerRecord server() {
        return new OfficialMcpServerRecord("everything", "src/everything",
                URI.create("https://github.com/modelcontextprotocol/servers/tree/commit/src/everything"),
                "76d64c822f5125032f89eb71dbdb94e42b434821", Map.of("package.json", "blob"), "a".repeat(64),
                "1.0.0", "官方测试服务", "MIT", "npx", List.of("-y", "server"), "server",
                List.of("MCP_TOKEN"), "官方说明");
    }

    private static McpInstallationPreview mcpPreview() {
        return new McpInstallationPreview(PREVIEW_ID, "confirm-mcp", InstallationScope.WORKSPACE, WORKSPACE_ID,
                URI.create("https://github.com/modelcontextprotocol/servers/tree/commit/src/everything"),
                "76d64c822f5125032f89eb71dbdb94e42b434821", "a".repeat(64), "npx", List.of("-y", "server"),
                List.of("MCP_TOKEN"), "官方说明", true, true, NOW.plusSeconds(300));
    }

    private static McpInstallationRecord mcpInstallation() {
        return new McpInstallationRecord(INSTALLATION_ID, UUID.randomUUID(), InstallationScope.WORKSPACE, WORKSPACE_ID,
                "user", McpInstallationStatus.STOPPED, "digest", NOW, NOW, NOW);
    }

    private static SkillInstallationPreview skillPreview() {
        return new SkillInstallationPreview(PREVIEW_ID, "confirm-skill", URI.create("https://github.com/octo/java-review"),
                "octo/java-review", "76d64c822f5125032f89eb71dbdb94e42b434821", "blob", "SKILL.md", "MIT",
                "b".repeat(64), "审查 Java", List.of(), InstallationScope.USER_GLOBAL, null, true, true, NOW.plusSeconds(300));
    }

    private static SkillInstallationRecord skillInstallation() {
        return new SkillInstallationRecord(INSTALLATION_ID, UUID.randomUUID(), InstallationScope.USER_GLOBAL, null,
                "user", SkillInstallationStatus.APPROVED, "digest", NOW, NOW, NOW);
    }
}
