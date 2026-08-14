package com.agent.web.skill;

import com.agent.core.tool.ToolCall;
import com.agent.core.tool.ToolDefinition;
import com.agent.core.tool.ToolRegistry;
import com.agent.core.tool.ToolResult;
import com.agent.core.tool.ToolInvocationContext;
import com.agent.core.tool.ToolRiskLevel;
import com.agent.core.intent.RequiredCapability;
import com.agent.web.capability.CapabilityManagementAuditSink;
import com.agent.web.capability.InstallationScope;
import com.agent.web.identity.Actor;
import com.agent.web.workspace.WorkspaceAccessService;
import com.agent.web.workspace.WorkspacePermission;
import com.agent.web.workspace.WorkspaceRecord;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubSkillInstallationServiceTest {
    private static final UUID WORKSPACE = UUID.fromString("7de5cf09-6ab9-46df-aa80-0adc5c66dc24");
    private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");

    @Test
    void defaultsToWorkspaceAndRequiresOneTimeConfirmation() throws Exception {
        Path root = Files.createTempDirectory("agent4j-skill");
        FakeRepository repository = new FakeRepository(
                new WorkspaceRecord(WORKSPACE, "user", "work", root, "repo",
                        WorkspacePermission.OWNER, NOW, NOW));
        GitHubSkillCatalogClient client = new GitHubSkillCatalogClient(
                (uri, timeout, maxBytes) -> response(uri), new com.fasterxml.jackson.databind.ObjectMapper(),
                java.net.URI.create("https://api.github.test/"), Duration.ofSeconds(2), 100_000);
        GitHubSkillInstallationService service = new GitHubSkillInstallationService(
                client, emptyTools(), () -> new Actor("user", "User"),
                new WorkspaceAccessService(repository, root, Clock.fixed(NOW, ZoneOffset.UTC)), repository,
                CapabilityManagementAuditSink.noop(), Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5),
                UUID::randomUUID);

        SkillInstallationPreview preview = service.preview(WORKSPACE, "octo/skills", null, null);
        SkillInstallationRecord installation = service.confirm(
                WORKSPACE, preview.previewId(), preview.confirmationToken(), null, null);

        assertThat(preview.scope()).isEqualTo(InstallationScope.WORKSPACE);
        assertThat(installation.workspaceId()).isEqualTo(WORKSPACE);
        assertThatThrownBy(() -> service.confirm(
                WORKSPACE, preview.previewId(), preview.confirmationToken(), null, null))
                .isInstanceOf(GitHubSkillInstallationService.InvalidConfirmationException.class);
    }

    @Test
    void keepsPreviewAvailableWhenAtomicConfirmationFails() throws Exception {
        Path root = Files.createTempDirectory("agent4j-skill-retry");
        FakeRepository repository = new FakeRepository(
                new WorkspaceRecord(WORKSPACE, "user", "work", root, "repo",
                        WorkspacePermission.OWNER, NOW, NOW));
        repository.failNextConfirmation = true;
        GitHubSkillCatalogClient client = new GitHubSkillCatalogClient(
                (uri, timeout, maxBytes) -> response(uri), new com.fasterxml.jackson.databind.ObjectMapper(),
                java.net.URI.create("https://api.github.test/"), Duration.ofSeconds(2), 100_000);
        GitHubSkillInstallationService service = new GitHubSkillInstallationService(
                client, emptyTools(), () -> new Actor("user", "User"),
                new WorkspaceAccessService(repository, root, Clock.fixed(NOW, ZoneOffset.UTC)), repository,
                CapabilityManagementAuditSink.noop(), Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5),
                UUID::randomUUID);
        SkillInstallationPreview preview = service.preview(WORKSPACE, "octo/skills", null, null);

        assertThatThrownBy(() -> service.confirm(WORKSPACE, preview.previewId(), preview.confirmationToken(), null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("测试确认失败");

        assertThat(service.confirm(WORKSPACE, preview.previewId(), preview.confirmationToken(), null, null))
                .isNotNull();
        assertThatThrownBy(() -> service.confirm(WORKSPACE, preview.previewId(), preview.confirmationToken(), null, null))
                .isInstanceOf(GitHubSkillInstallationService.InvalidConfirmationException.class);
    }

    private static GitHubSkillCatalogClient.HttpResponse response(java.net.URI uri) {
        String path = uri.getPath() + (uri.getQuery() == null ? "" : "?" + uri.getQuery());
        return switch (path) {
            case "/repos/octo/skills" -> new GitHubSkillCatalogClient.HttpResponse(200,
                    "{\"full_name\":\"octo/skills\",\"html_url\":\"https://github.com/octo/skills\",\"default_branch\":\"main\",\"description\":\"skill\",\"license\":null}");
            case "/repos/octo/skills/commits/main" -> new GitHubSkillCatalogClient.HttpResponse(200,
                    "{\"sha\":\"76d64c822f5125032f89eb71dbdb94e42b434821\"}");
            case "/repos/octo/skills/contents/SKILL.md?ref=76d64c822f5125032f89eb71dbdb94e42b434821" -> new GitHubSkillCatalogClient.HttpResponse(200,
                    "{\"type\":\"file\",\"path\":\"SKILL.md\",\"sha\":\"blob\",\"encoding\":\"base64\",\"content\":\"LS0tCm5hbWU6IGphdmEtcmV2aWV3CnZlcnNpb246IDEuMC4wCmRlc2NyaXB0aW9uOiBSZXZpZXcgSmF2YSBjaGFuZ2VzCnRyaWdnZXJzOgogIC0gcmV2aWV3IEphdmEKdG9vbHM6CiAgLSBjb2RlLnBhdGNoCi0tLQpSZXZpZXcgdGhlIGRpZmYu\"}");
            default -> throw new IllegalStateException(path);
        };
    }

    private static ToolRegistry emptyTools() {
        return new ToolRegistry() {
            public void register(ToolDefinition definition) { throw new UnsupportedOperationException(); }
            public void registerAll(List<ToolDefinition> definitions) { throw new UnsupportedOperationException(); }
            private final ToolDefinition definition = new ToolDefinition(
                    "code.patch", "修改代码", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
                    Set.of(RequiredCapability.TOOL), ToolRiskLevel.LOW, Duration.ofSeconds(1),
                    (call, context) -> new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());
            public Optional<ToolDefinition> find(String name) { return "code.patch".equals(name) ? Optional.of(definition) : Optional.empty(); }
            public List<ToolDefinition> list() { return List.of(definition); }
            public ToolResult execute(ToolCall call, ToolInvocationContext context) { throw new UnsupportedOperationException(); }
            public void close() { }
        };
    }

    private static final class FakeRepository implements SkillInstallationRepository, com.agent.web.workspace.WorkspaceRepository {
        private final WorkspaceRecord workspace;
        private boolean failNextConfirmation;
        private FakeRepository(WorkspaceRecord workspace) { this.workspace = workspace; }
        public SkillInstallationRecord confirmSkill(SkillSnapshotRecord snapshot, SkillInstallationRecord installation, com.agent.web.capability.CapabilityManagementAuditEvent auditEvent) {
            if (failNextConfirmation) {
                failNextConfirmation = false;
                throw new IllegalStateException("测试确认失败");
            }
            return installation;
        }
        public List<SkillInstallationRecord> findInstallations(String actorUserId, UUID workspaceId) { return List.of(); }
        public SkillInstallationRecord removeInstallation(UUID skillInstallationId, String actorUserId, UUID workspaceId, long expectedVersion, com.agent.web.capability.CapabilityManagementAuditEvent auditEvent) { throw new IllegalStateException(); }
        public SkillInstallationRecord transition(UUID skillInstallationId, long expectedVersion, SkillInstallationStatus from, SkillInstallationStatus to) { throw new UnsupportedOperationException(); }
        public Optional<WorkspaceRecord> findWorkspace(UUID id, String userId) { return WORKSPACE.equals(id) && "user".equals(userId) ? Optional.of(workspace) : Optional.empty(); }
        public List<WorkspaceRecord> findWorkspaces(String userId) { return List.of(workspace); }
        public WorkspaceRecord createWorkspace(UUID id, Actor owner, String displayName, Path path, String repositoryId, Instant now) { throw new UnsupportedOperationException(); }
        public WorkspaceRecord ensureDefaultWorkspace(UUID id, Actor owner, String displayName, Path path, String repositoryId, Instant now) { throw new UnsupportedOperationException(); }
        public void ensureUser(Actor actor, Instant now) { }
        public void grantMember(UUID id, String userId, WorkspacePermission permission, Instant now) { }
    }
}
