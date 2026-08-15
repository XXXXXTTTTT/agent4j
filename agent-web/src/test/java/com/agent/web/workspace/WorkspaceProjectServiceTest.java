package com.agent.web.workspace;

import com.agent.web.identity.Actor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceProjectServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");

    @TempDir
    Path temp;

    @Test
    void createsEmptyChildDirectoryAndRegistersWorkspace() throws Exception {
        FakeRepository repository = new FakeRepository();
        WorkspaceAccessService access = new WorkspaceAccessService(
                repository, temp, Clock.fixed(NOW, ZoneOffset.UTC));
        WorkspaceProjectService service = new WorkspaceProjectService(
                access, temp, Clock.fixed(NOW, ZoneOffset.UTC));

        WorkspaceRecord workspace = service.create(
                new Actor("local", "Local"), "Demo", "demo", "demo-repository");

        assertThat(workspace.workspacePath()).isEqualTo(temp.resolve("demo").toRealPath());
        assertThat(Files.isDirectory(workspace.workspacePath())).isTrue();
        assertThat(Files.isDirectory(workspace.workspacePath().resolve(".git"))).isTrue();
        assertThat(repository.created).isEqualTo(workspace);
    }

    @Test
    void rejectsDirectoryNameContainingPathSeparatorsAndExistingDirectory() throws Exception {
        FakeRepository repository = new FakeRepository();
        WorkspaceAccessService access = new WorkspaceAccessService(
                repository, temp, Clock.fixed(NOW, ZoneOffset.UTC));
        WorkspaceProjectService service = new WorkspaceProjectService(
                access, temp, Clock.fixed(NOW, ZoneOffset.UTC));
        Files.createDirectory(temp.resolve("existing"));

        assertThatThrownBy(() -> service.create(
                new Actor("local", "Local"), "Demo", "nested/demo", "repo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("目录名");
        assertThatThrownBy(() -> service.create(
                new Actor("local", "Local"), "Demo", "existing", "repo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void recursivelyRemovesProjectWhenWorkspaceRegistrationFails() throws Exception {
        FakeRepository repository = new FakeRepository();
        repository.createFailure = new IllegalStateException("数据库注册失败");
        WorkspaceAccessService access = new WorkspaceAccessService(
                repository, temp, Clock.fixed(NOW, ZoneOffset.UTC));
        WorkspaceProjectService service = new WorkspaceProjectService(
                access, temp, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.create(
                new Actor("local", "Local"), "Demo", "failed", "repo"))
                .isSameAs(repository.createFailure);
        assertThat(Files.exists(temp.resolve("failed"))).isFalse();
    }

    private static final class FakeRepository implements WorkspaceRepository {
        private WorkspaceRecord created;
        private RuntimeException createFailure;

        @Override
        public Optional<WorkspaceRecord> findWorkspace(UUID workspaceId, String userId) {
            return Optional.ofNullable(created);
        }

        @Override
        public List<WorkspaceRecord> findWorkspaces(String userId) {
            return created == null ? List.of() : List.of(created);
        }

        @Override
        public WorkspaceRecord createWorkspace(UUID workspaceId, Actor owner, String displayName,
                Path workspacePath, String repositoryId, Instant now) {
            if (createFailure != null) {
                throw createFailure;
            }
            created = new WorkspaceRecord(workspaceId, owner.userId(), displayName,
                    workspacePath, repositoryId, WorkspacePermission.OWNER, now, now);
            return created;
        }

        @Override
        public WorkspaceRecord ensureDefaultWorkspace(UUID workspaceId, Actor owner,
                String displayName, Path workspacePath, String repositoryId, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void ensureUser(Actor actor, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void grantMember(UUID workspaceId, String userId,
                WorkspacePermission permission, Instant now) {
            throw new UnsupportedOperationException();
        }
    }
}
