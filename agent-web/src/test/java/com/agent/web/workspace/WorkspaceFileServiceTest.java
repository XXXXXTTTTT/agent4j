package com.agent.web.workspace;

import com.agent.web.identity.Actor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceFileServiceTest {

    private static final UUID WORKSPACE_ID =
            UUID.fromString("f4c2a1bb-0f6d-4df2-89db-0b31e20e4c0e");
    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");

    @TempDir
    Path temp;

    @Test
    void listsDirectoriesBeforeFilesAndReadsAndWritesUtf8WithSha() throws Exception {
        Path project = Files.createDirectory(temp.resolve("project"));
        Files.createDirectory(project.resolve("z-folder"));
        Files.writeString(project.resolve("b.txt"), "你好");
        Files.writeString(project.resolve("a.txt"), "a");
        FakeRepository repository = new FakeRepository(project);
        WorkspaceFileService service = service(repository, temp);

        List<WorkspaceFileEntry> entries = service.list(WORKSPACE_ID, "local", "");

        assertThat(entries).extracting(WorkspaceFileEntry::path)
                .containsExactly("z-folder", "a.txt", "b.txt");
        WorkspaceFileContent original = service.read(WORKSPACE_ID, "local", "b.txt");
        assertThat(original.content()).isEqualTo("你好");
        WorkspaceFileContent updated = service.write(
                WORKSPACE_ID, "local", "b.txt", "更新", original.sha256());
        assertThat(updated.content()).isEqualTo("更新");
        assertThat(Files.readString(project.resolve("b.txt"))).isEqualTo("更新");
    }

    @Test
    void rejectsTraversalAndShaConflicts() throws Exception {
        Path project = Files.createDirectory(temp.resolve("project"));
        Files.writeString(project.resolve("main.txt"), "old");
        FakeRepository repository = new FakeRepository(project);
        WorkspaceFileService service = service(repository, temp);

        assertThatThrownBy(() -> service.read(WORKSPACE_ID, "local", "../main.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("相对路径");
        assertThatThrownBy(() -> service.write(
                WORKSPACE_ID, "local", "main.txt", "new", "wrong-sha"))
                .isInstanceOf(WorkspaceFileService.FileConflictException.class);
    }

    private WorkspaceFileService service(FakeRepository repository, Path root) {
        WorkspaceAccessService access = new WorkspaceAccessService(
                repository, root, Clock.fixed(NOW, ZoneOffset.UTC));
        return new WorkspaceFileService(access, 1024 * 1024);
    }

    private static final class FakeRepository implements WorkspaceRepository {
        private final WorkspaceRecord workspace;

        private FakeRepository(Path path) {
            workspace = new WorkspaceRecord(WORKSPACE_ID, "local", "Project", path,
                    "project", WorkspacePermission.OWNER, NOW, NOW);
        }

        @Override
        public Optional<WorkspaceRecord> findWorkspace(UUID workspaceId, String userId) {
            return WORKSPACE_ID.equals(workspaceId) && "local".equals(userId)
                    ? Optional.of(workspace) : Optional.empty();
        }

        @Override
        public List<WorkspaceRecord> findWorkspaces(String userId) {
            return List.of(workspace);
        }

        @Override
        public WorkspaceRecord createWorkspace(UUID workspaceId, Actor owner, String displayName,
                Path workspacePath, String repositoryId, Instant now) {
            throw new UnsupportedOperationException();
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
