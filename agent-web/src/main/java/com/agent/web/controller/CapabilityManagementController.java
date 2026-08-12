package com.agent.web.controller;

import com.agent.web.capability.InstallationScope;
import com.agent.web.mcp.catalog.OfficialMcpCatalogClient;
import com.agent.web.mcp.catalog.OfficialMcpServerRecord;
import com.agent.web.mcp.installation.McpInstallationPreview;
import com.agent.web.mcp.installation.McpInstallationRecord;
import com.agent.web.mcp.installation.McpInstallationService;
import com.agent.web.skill.GitHubSkillCatalogClient;
import com.agent.web.skill.GitHubSkillInstallationService;
import com.agent.web.skill.GitHubSkillRepository;
import com.agent.web.skill.SkillInstallationPreview;
import com.agent.web.skill.SkillInstallationRecord;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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

/** MCP 官方目录与 GitHub Skill 的受治理管理 API。 */
@RestController
@ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
public final class CapabilityManagementController {
    private final OfficialMcpCatalogClient mcpCatalog;
    private final McpInstallationService mcpInstallations;
    private final GitHubSkillCatalogClient skillCatalog;
    private final GitHubSkillInstallationService skillInstallations;

    public CapabilityManagementController(OfficialMcpCatalogClient mcpCatalog,
                                          McpInstallationService mcpInstallations,
                                          GitHubSkillCatalogClient skillCatalog,
                                          GitHubSkillInstallationService skillInstallations) {
        this.mcpCatalog = Objects.requireNonNull(mcpCatalog, "mcpCatalog 不能为空");
        this.mcpInstallations = Objects.requireNonNull(mcpInstallations, "mcpInstallations 不能为空");
        this.skillCatalog = Objects.requireNonNull(skillCatalog, "skillCatalog 不能为空");
        this.skillInstallations = Objects.requireNonNull(skillInstallations, "skillInstallations 不能为空");
    }

    @GetMapping("/api/mcp/catalog")
    public CatalogView mcpCatalog() {
        return CatalogView.from(mcpCatalog.fetchCatalogResult());
    }

    @PostMapping("/api/workspaces/{workspaceId}/mcp/installations/preview")
    public McpInstallationPreviewView previewMcp(@PathVariable UUID workspaceId,
                                                  @Valid @RequestBody McpPreviewRequest request) {
        OfficialMcpServerRecord server = mcpCatalog.fetchCatalogResult().records().stream()
                .filter(value -> value.serviceId().equals(request.serverKey()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("MCP 服务不存在于官方目录: " + request.serverKey()));
        return McpInstallationPreviewView.from(mcpInstallations.preview(workspaceId, server,
                request.scope(), request.targetWorkspaceId()));
    }

    @PostMapping("/api/workspaces/{workspaceId}/mcp/installations")
    public ResponseEntity<InstallationView> installMcp(@PathVariable UUID workspaceId,
                                                        @Valid @RequestBody ConfirmInstallationRequest request) {
        return ResponseEntity.status(201).body(InstallationView.from(mcpInstallations.confirm(workspaceId,
                request.previewId(), request.confirmationToken(), request.scope(), request.targetWorkspaceId())));
    }

    @GetMapping("/api/workspaces/{workspaceId}/mcp/installations")
    public List<InstallationView> listMcp(@PathVariable UUID workspaceId) {
        return mcpInstallations.list(workspaceId).stream().map(InstallationView::from).toList();
    }

    @DeleteMapping("/api/workspaces/{workspaceId}/mcp/installations/{installationId}")
    public InstallationView uninstallMcp(@PathVariable UUID workspaceId, @PathVariable UUID installationId) {
        return InstallationView.from(mcpInstallations.uninstall(workspaceId, installationId));
    }

    @GetMapping("/api/skills/search")
    public List<GitHubSkillRepository> searchSkills(@RequestParam("q") String query) {
        return skillCatalog.search(query);
    }

    @PostMapping("/api/workspaces/{workspaceId}/skills/preview")
    public SkillInstallationPreview previewSkill(@PathVariable UUID workspaceId,
                                                   @Valid @RequestBody SkillPreviewRequest request) {
        return skillInstallations.preview(workspaceId, request.repository(),
                request.scope(), request.targetWorkspaceId());
    }

    @PostMapping("/api/workspaces/{workspaceId}/skills")
    public ResponseEntity<SkillInstallationView> installSkill(@PathVariable UUID workspaceId,
                                                               @Valid @RequestBody ConfirmSkillRequest request) {
        return ResponseEntity.status(201).body(SkillInstallationView.from(skillInstallations.confirm(workspaceId,
                request.previewId(), request.confirmationToken(), request.scope(), request.targetWorkspaceId())));
    }

    @GetMapping("/api/workspaces/{workspaceId}/skills")
    public List<SkillInstallationView> listSkills(@PathVariable UUID workspaceId) {
        return skillInstallations.list(workspaceId).stream().map(SkillInstallationView::from).toList();
    }

    @DeleteMapping("/api/workspaces/{workspaceId}/skills/{skillId}")
    public SkillInstallationView uninstallSkill(@PathVariable UUID workspaceId, @PathVariable UUID skillId) {
        return SkillInstallationView.from(skillInstallations.uninstall(workspaceId, skillId));
    }

    public record McpPreviewRequest(@NotBlank String serverKey, InstallationScope scope, UUID targetWorkspaceId) { }
    public record ConfirmInstallationRequest(@NotNull UUID previewId, @NotBlank String confirmationToken,
                                              @NotNull InstallationScope scope, UUID targetWorkspaceId) { }
    public record SkillPreviewRequest(@NotBlank String repository, InstallationScope scope, UUID targetWorkspaceId) { }
    public record ConfirmSkillRequest(@NotNull UUID previewId, @NotBlank String confirmationToken,
                                      @NotNull InstallationScope scope, UUID targetWorkspaceId) { }

    public record CatalogView(String repository, String commitSha, java.time.Instant fetchedAt,
                              java.time.Instant expiresAt, String etag, String status,
                              List<OfficialMcpServerRecord> servers, java.util.Map<String, String> errors) {
        static CatalogView from(OfficialMcpCatalogClient.CatalogResult result) {
            return new CatalogView(result.repository(), result.commitSha(), result.fetchedAt(), result.expiresAt(),
                    result.etag(), result.status(), result.records(), result.errors());
        }
    }

    public record McpInstallationPreviewView(UUID previewId, String confirmationToken, java.net.URI sourceUrl, String commitSha,
                                              String metadataSha256, String command, List<String> arguments,
                                              List<String> environmentNames, String summary,
                                              InstallationScope scope, UUID workspaceId,
                                              boolean requiresConfirmation, boolean sideEffectFree,
                                              java.time.Instant expiresAt) {
        static McpInstallationPreviewView from(McpInstallationPreview value) {
            return new McpInstallationPreviewView(value.previewId(), value.confirmationToken(), value.sourceUrl(), value.commitSha(),
                    value.metadataSha256(), value.command(), value.arguments(), value.environmentVariableNames(),
                    value.summary(), value.scope(), value.workspaceId(), value.requiresConfirmation(),
                    value.sideEffectFree(), value.expiresAt());
        }
    }

    public record InstallationView(UUID installationId, UUID snapshotId, InstallationScope scope,
                                   UUID workspaceId, String actorUserId, String status,
                                   java.time.Instant createdAt, java.time.Instant confirmedAt,
                                   java.time.Instant updatedAt) {
        static InstallationView from(McpInstallationRecord value) {
            return new InstallationView(value.installationId(), value.snapshotId(), value.scope(), value.workspaceId(),
                    value.actorUserId(), value.status().name(), value.createdAt(), value.confirmedAt(), value.updatedAt());
        }
    }

    public record SkillInstallationView(UUID skillInstallationId, UUID skillSnapshotId, InstallationScope scope,
                                        UUID workspaceId, String actorUserId, String status,
                                        java.time.Instant createdAt, java.time.Instant confirmedAt,
                                        java.time.Instant updatedAt) {
        static SkillInstallationView from(SkillInstallationRecord value) {
            return new SkillInstallationView(value.skillInstallationId(), value.skillSnapshotId(), value.scope(),
                    value.workspaceId(), value.actorUserId(), value.status().name(), value.createdAt(),
                    value.confirmedAt(), value.updatedAt());
        }
    }
}
