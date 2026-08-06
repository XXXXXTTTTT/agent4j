package com.agent.web.workspace;

import com.agent.web.identity.Actor;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceAccessServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-07T00:00:00Z");
    private static final UUID WORKSPACE_ID =
            UUID.fromString("f4c2a1bb-0f6d-4df2-89db-0b31e20e4c0e");

    @Test
    void validatesWorkspaceWithinConfiguredRootAndRequiresPermission() throws Exception {
        Path root = Files.createTempDirectory("agent4j-root");
        Path workspacePath = Files.createDirectory(root.resolve("repo"));
        FakeRepository repository = new FakeRepository();
        repository.workspace = new WorkspaceRecord(
                WORKSPACE_ID,
                "local",
                "repo",
                workspacePath,
                "repo-id",
                WorkspacePermission.OPERATOR,
                NOW,
                NOW);
        WorkspaceAccessService service = new WorkspaceAccessService(
                repository,
                root,
                Clock.fixed(NOW, ZoneOffset.UTC));

        WorkspaceRecord result = service.requireWorkspace(
                WORKSPACE_ID, "local", WorkspacePermission.OPERATOR);

        assertThat(result.workspacePath()).isEqualTo(workspacePath.toRealPath());
        assertThatThrownBy(() -> service.validateWorkspacePath(root.resolve("missing")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("现有目录");
    }

    private static final class FakeRepository implements WorkspaceRepository {
        private WorkspaceRecord workspace;

        @Override
        public Optional<WorkspaceRecord> findWorkspace(UUID workspaceId, String userId) {
            return workspace != null
                    && workspace.workspaceId().equals(workspaceId)
                    && workspace.ownerUserId().equals(userId)
                    ? Optional.of(workspace)
                    : Optional.empty();
        }

        @Override
        public java.util.List<WorkspaceRecord> findWorkspaces(String userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorkspaceRecord createWorkspace(
                UUID workspaceId,
                Actor owner,
                String displayName,
                Path workspacePath,
                String repositoryId,
                Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorkspaceRecord ensureDefaultWorkspace(
                UUID workspaceId,
                Actor owner,
                String displayName,
                Path workspacePath,
                String repositoryId,
                Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void ensureUser(Actor actor, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void grantMember(
                UUID workspaceId,
                String userId,
                WorkspacePermission permission,
                Instant now) {
            throw new UnsupportedOperationException();
        }
    }
}
