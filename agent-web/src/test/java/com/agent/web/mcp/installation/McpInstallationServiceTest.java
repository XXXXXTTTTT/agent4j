package com.agent.web.mcp.installation;

import com.agent.web.capability.CapabilityManagementAuditEvent;
import com.agent.web.capability.CapabilityManagementAuditSink;
import com.agent.web.capability.InstallationScope;
import com.agent.web.identity.Actor;
import com.agent.web.mcp.catalog.OfficialMcpServerRecord;
import com.agent.web.workspace.WorkspaceAccessService;
import com.agent.web.workspace.WorkspacePermission;
import com.agent.web.workspace.WorkspaceRecord;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpInstallationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");
    private static final Actor ACTOR = new Actor("mcp-user", "MCP 用户");
    private static final UUID WORKSPACE_ID = UUID.fromString("7de5cf09-6ab9-46df-aa80-0adc5c66dc24");
    private static final UUID OTHER_WORKSPACE_ID = UUID.fromString("871c5c2f-30d7-4a6d-8874-c62d14d52c4b");

    @Test
    void previewsWorkspaceInstallationWithoutPersistingOrAuditing() throws Exception {
        FakeRepository repository = new FakeRepository();
        CapturingAuditSink audit = new CapturingAuditSink();
        McpInstallationService service = service(repository, audit, ACTOR, WORKSPACE_ID, OTHER_WORKSPACE_ID);

        McpInstallationPreview preview = service.preview(WORKSPACE_ID, server(), null, null);

        assertThat(preview.scope()).isEqualTo(InstallationScope.WORKSPACE);
        assertThat(preview.workspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(preview.requiresConfirmation()).isTrue();
        assertThat(preview.sideEffectFree()).isTrue();
        assertThat(preview.environmentVariableNames()).containsExactly("MCP_TOKEN");
        assertThat(repository.savedSnapshots).isEmpty();
        assertThat(repository.savedInstallations).isEmpty();
        assertThat(audit.events).isEmpty();
    }

    @Test
    void confirmsOnceAndPersistsOnlyImmutableSourceAndEnvironmentNames() throws Exception {
        FakeRepository repository = new FakeRepository();
        CapturingAuditSink audit = new CapturingAuditSink();
        McpInstallationService service = service(repository, audit, ACTOR, WORKSPACE_ID, OTHER_WORKSPACE_ID);
        McpInstallationPreview preview = service.preview(WORKSPACE_ID, server(), InstallationScope.WORKSPACE, WORKSPACE_ID);

        McpInstallationRecord installation = service.confirm(
                WORKSPACE_ID, preview.previewId(), preview.confirmationToken(), InstallationScope.WORKSPACE, WORKSPACE_ID);

        assertThat(installation.scope()).isEqualTo(InstallationScope.WORKSPACE);
        assertThat(installation.workspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(installation.actorUserId()).isEqualTo(ACTOR.userId());
        assertThat(installation.status()).isEqualTo(McpInstallationStatus.STOPPED);
        assertThat(repository.savedSnapshots).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.commitSha()).isEqualTo("76d64c822f5125032f89eb71dbdb94e42b434821");
            assertThat(snapshot.blobShas()).containsEntry("package.json", "blob-package");
            assertThat(snapshot.metadataSha256()).isEqualTo("a".repeat(64));
            assertThat(snapshot.environmentVariableNames()).containsExactly("MCP_TOKEN");
            assertThat(snapshot.environmentVariableNames()).noneMatch(value -> value.contains("secret-value"));
        });
        assertThat(repository.auditEvents).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("MCP_INSTALLATION_CONFIRMED");
            assertThat(event.actorUserId()).isEqualTo(ACTOR.userId());
            assertThat(event.workspaceId()).isEqualTo(WORKSPACE_ID);
            assertThat(event.installationId()).isEqualTo(installation.installationId());
            assertThat(event.sourceCommitSha()).isEqualTo("76d64c822f5125032f89eb71dbdb94e42b434821");
        });
        assertThatThrownBy(() -> service.confirm(
                WORKSPACE_ID, preview.previewId(), preview.confirmationToken(), InstallationScope.WORKSPACE, WORKSPACE_ID))
                .isInstanceOf(McpInstallationService.InvalidConfirmationException.class);
    }

    @Test
    void requiresExplicitGlobalScopeAndKeepsGlobalInstallationOutsideWorkspaceRows() throws Exception {
        FakeRepository repository = new FakeRepository();
        McpInstallationService service = service(repository, new CapturingAuditSink(), ACTOR, WORKSPACE_ID, OTHER_WORKSPACE_ID);

        McpInstallationPreview preview = service.preview(
                WORKSPACE_ID, server(), InstallationScope.USER_GLOBAL, null);
        McpInstallationRecord installation = service.confirm(
                WORKSPACE_ID, preview.previewId(), preview.confirmationToken(), InstallationScope.USER_GLOBAL, null);

        assertThat(installation.scope()).isEqualTo(InstallationScope.USER_GLOBAL);
        assertThat(installation.workspaceId()).isNull();
        assertThatThrownBy(() -> service.preview(
                WORKSPACE_ID, server(), InstallationScope.USER_GLOBAL, OTHER_WORKSPACE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetWorkspaceId");
    }

    @Test
    void rejectsGlobalInstallationFromWorkspaceOwnedByAnotherUser() throws Exception {
        FakeRepository repository = new FakeRepository();
        McpInstallationService service = service(repository, new CapturingAuditSink(), ACTOR, WORKSPACE_ID, OTHER_WORKSPACE_ID);

        assertThatThrownBy(() -> service.preview(
                OTHER_WORKSPACE_ID, server(), InstallationScope.USER_GLOBAL, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户自己的工作区");
    }

    private McpInstallationService service(
            FakeRepository repository,
            CapturingAuditSink audit,
            Actor actor,
            UUID workspaceId,
            UUID otherWorkspaceId) throws Exception {
        Path root = Files.createTempDirectory("agent4j-mcp-installation");
        Path ownPath = Files.createDirectory(root.resolve("own"));
        Path otherPath = Files.createDirectory(root.resolve("other"));
        repository.workspaces = Map.of(
                workspaceId, new WorkspaceRecord(workspaceId, actor.userId(), "own", ownPath,
                        "own-repository", WorkspacePermission.OWNER, NOW, NOW),
                otherWorkspaceId, new WorkspaceRecord(otherWorkspaceId, "another-user", "other", otherPath,
                        "other-repository", WorkspacePermission.OWNER, NOW, NOW));
        WorkspaceAccessService access = new WorkspaceAccessService(repository, root, Clock.fixed(NOW, ZoneOffset.UTC));
        return new McpInstallationService(
                () -> actor,
                access,
                repository,
                audit,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(5),
                () -> UUID.fromString("e2d2e8b6-9550-4ab5-b50e-6ef11bffaf6d"));
    }

    private OfficialMcpServerRecord server() {
        return new OfficialMcpServerRecord(
                "everything", "src/everything", URI.create("https://github.com/modelcontextprotocol/servers/tree/76d64c822f5125032f89eb71dbdb94e42b434821/src/everything"),
                "76d64c822f5125032f89eb71dbdb94e42b434821", Map.of("package.json", "blob-package"),
                "a".repeat(64), "2.0.0", "测试服务", "MIT", "npx",
                List.of("-y", "@modelcontextprotocol/server-everything@2.0.0"), "mcp-server-everything",
                List.of("MCP_TOKEN"), "# Everything");
    }

    private static final class FakeRepository implements McpInstallationRepository, com.agent.web.workspace.WorkspaceRepository {
        private Map<UUID, WorkspaceRecord> workspaces = Map.of();
        private final List<McpSourceSnapshot> savedSnapshots = new ArrayList<>();
        private final List<McpInstallationRecord> savedInstallations = new ArrayList<>();
        private final List<CapabilityManagementAuditEvent> auditEvents = new ArrayList<>();

        @Override public McpInstallationRecord confirmInstallation(McpInstallationCommand command) { savedSnapshots.add(command.snapshot()); savedInstallations.add(command.installation()); auditEvents.add(command.auditEvent()); return command.installation(); }
        @Override public List<McpInstallationRecord> findInstallations(String actorUserId, UUID workspaceId) { return List.copyOf(savedInstallations); }
        @Override public boolean removeInstallation(UUID installationId, String actorUserId, UUID workspaceId, long expectedVersion, CapabilityManagementAuditEvent auditEvent) { return savedInstallations.removeIf(value -> value.installationId().equals(installationId) && value.version() == expectedVersion); }
        @Override public McpInstallationRecord transition(UUID installationId, long expectedVersion, McpInstallationStatus from, McpInstallationStatus to, String runtimeError, String containerId) { throw new UnsupportedOperationException(); }
        @Override public Optional<WorkspaceRecord> findWorkspace(UUID workspaceId, String userId) { return Optional.ofNullable(workspaces.get(workspaceId)); }
        @Override public List<WorkspaceRecord> findWorkspaces(String userId) { return List.of(); }
        @Override public WorkspaceRecord createWorkspace(UUID workspaceId, Actor owner, String displayName, Path workspacePath, String repositoryId, Instant now) { throw new UnsupportedOperationException(); }
        @Override public WorkspaceRecord ensureDefaultWorkspace(UUID workspaceId, Actor owner, String displayName, Path workspacePath, String repositoryId, Instant now) { throw new UnsupportedOperationException(); }
        @Override public void ensureUser(Actor actor, Instant now) { throw new UnsupportedOperationException(); }
        @Override public void grantMember(UUID workspaceId, String userId, WorkspacePermission permission, Instant now) { throw new UnsupportedOperationException(); }
    }

    private static final class CapturingAuditSink implements CapabilityManagementAuditSink {
        private final List<CapabilityManagementAuditEvent> events = new ArrayList<>();
        @Override public void record(CapabilityManagementAuditEvent event) { events.add(event); }
    }
}
