package com.agent.web.workspace;

import com.agent.web.config.WorkspaceImportProperties;
import com.agent.web.identity.Actor;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class WorkspaceImportServiceTest {

    private static final Actor ACTOR = new Actor("local", "本地用户");
    private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");

    @TempDir
    Path root;

    @Test
    void extractsArchiveAndRegistersWorkspace() throws Exception {
        Path archive = archive("src/Main.java", "class Main {}\n");
        WorkspaceRepository repository = Mockito.mock(WorkspaceRepository.class);
        WorkspaceAccessService access = access(repository);
        when(repository.createWorkspace(any(), eq(ACTOR), eq("demo"), any(), eq("repo"), any()))
                .thenAnswer(invocation -> record(invocation.getArgument(0), invocation.getArgument(3)));

        WorkspaceRecord imported = service(access).importArchive(ACTOR, "demo", "repo", archive);

        assertEquals("demo", imported.displayName());
        Path importedRoot = root.resolve(".agent4j/imports").resolve(imported.workspaceId().toString());
        assertTrue(Files.exists(importedRoot.resolve("src/Main.java")));
        try (Git ignored = Git.open(importedRoot.toFile())) {
            assertEquals(importedRoot.toRealPath(), ignored.getRepository().getWorkTree().toPath().toRealPath());
        }
    }

    @Test
    void rejectsParentTraversal() throws Exception {
        Path archive = archive("../escape.txt", "x");
        WorkspaceImportService service = service(access(Mockito.mock(WorkspaceRepository.class)));
        WorkspaceImportService.ImportFormatException failure = assertThrows(
                WorkspaceImportService.ImportFormatException.class,
                () -> service.importArchive(ACTOR, "demo", "repo", archive));
        assertEquals("ZIP 条目路径越界: ../escape.txt", failure.getMessage());
    }

    @Test
    void rejectsAbsoluteEntry() throws Exception {
        Path archive = archive("/escape.txt", "x");
        WorkspaceImportService service = service(access(Mockito.mock(WorkspaceRepository.class)));
        assertThrows(WorkspaceImportService.ImportFormatException.class,
                () -> service.importArchive(ACTOR, "demo", "repo", archive));
    }

    @Test
    void rejectsDuplicateNormalizedPaths() throws Exception {
        Path archive = duplicateArchive();
        WorkspaceImportService service = service(access(Mockito.mock(WorkspaceRepository.class)));
        WorkspaceImportService.ImportFormatException failure = assertThrows(
                WorkspaceImportService.ImportFormatException.class,
                () -> service.importArchive(ACTOR, "demo", "repo", archive));
        assertTrue(failure.getMessage().contains("重复"));
    }

    @Test
    void enforcesArchiveByteLimit() throws Exception {
        Path archive = archive("file.txt", "0123456789");
        WorkspaceImportService service = new WorkspaceImportService(
                access(Mockito.mock(WorkspaceRepository.class)), root,
                new WorkspaceImportProperties(4, 1024, 10));
        assertThrows(WorkspaceImportService.ImportLimitExceededException.class,
                () -> service.importArchive(ACTOR, "demo", "repo", archive));
    }

    @Test
    void enforcesExtractedByteLimit() throws Exception {
        Path archive = archive("file.txt", "0123456789");
        WorkspaceImportService service = new WorkspaceImportService(
                access(Mockito.mock(WorkspaceRepository.class)), root,
                new WorkspaceImportProperties(1024, 4, 10));
        assertThrows(WorkspaceImportService.ImportLimitExceededException.class,
                () -> service.importArchive(ACTOR, "demo", "repo", archive));
    }

    @Test
    void enforcesFileCountLimit() throws Exception {
        Path archive = archive("a.txt", "a", "b.txt", "b");
        WorkspaceImportService service = new WorkspaceImportService(
                access(Mockito.mock(WorkspaceRepository.class)), root,
                new WorkspaceImportProperties(1024, 1024, 1));
        assertThrows(WorkspaceImportService.ImportLimitExceededException.class,
                () -> service.importArchive(ACTOR, "demo", "repo", archive));
    }

    @Test
    void rejectsNonZipContent() throws Exception {
        Path archive = Files.createTempFile(root, "not-zip-", ".bin");
        Files.writeString(archive, "plain text");
        WorkspaceImportService service = service(access(Mockito.mock(WorkspaceRepository.class)));
        assertThrows(WorkspaceImportService.ImportFormatException.class,
                () -> service.importArchive(ACTOR, "demo", "repo", archive));
    }

    @Test
    void deletesImportedFilesWhenRegistrationFails() throws Exception {
        Path archive = archive("file.txt", "x");
        WorkspaceRepository repository = Mockito.mock(WorkspaceRepository.class);
        WorkspaceAccessService access = access(repository);
        when(repository.createWorkspace(any(), eq(ACTOR), eq("demo"), any(), eq("repo"), any()))
                .thenThrow(new IllegalStateException("数据库注册失败"));

        assertThrows(IllegalStateException.class,
                () -> service(access).importArchive(ACTOR, "demo", "repo", archive));
        Path imports = root.resolve(".agent4j/imports");
        assertTrue(Files.notExists(imports) || Files.list(imports).findAny().isEmpty());
    }

    @Test
    void writesWorkspaceAuditTimeUsingChinaStandardTime() throws Exception {
        Path archive = archive("file.txt", "x");
        WorkspaceRepository repository = Mockito.mock(WorkspaceRepository.class);
        WorkspaceAccessService access = access(repository);
        when(repository.createWorkspace(any(), eq(ACTOR), eq("demo"), any(), eq("repo"), any()))
                .thenAnswer(invocation -> record(invocation.getArgument(0), invocation.getArgument(3)));
        Logger logger = (Logger) LoggerFactory.getLogger("com.agent.audit.workspace");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new WorkspaceImportService(access, root,
                    new WorkspaceImportProperties(1024, 1024, 10),
                    Clock.fixed(NOW, ZoneOffset.UTC))
                    .importArchive(ACTOR, "demo", "repo", archive);

            assertTrue(appender.list.getLast().getFormattedMessage()
                    .contains("time=2026-08-10T08:00+08:00"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private WorkspaceImportService service(WorkspaceAccessService access) {
        return new WorkspaceImportService(access, root,
                new WorkspaceImportProperties(1024 * 1024, 1024 * 1024, 10));
    }

    private WorkspaceAccessService access(WorkspaceRepository repository) {
        return new WorkspaceAccessService(repository, root,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private WorkspaceRecord record(UUID id, Path path) {
        return new WorkspaceRecord(id, "local", "demo", path, "repo",
                WorkspacePermission.OWNER, NOW, NOW);
    }

    private Path archive(String... entries) throws Exception {
        Path archive = Files.createTempFile(root, "project-", ".zip");
        try (OutputStream output = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(output)) {
            for (int i = 0; i < entries.length; i += 2) {
                zip.putNextEntry(new ZipEntry(entries[i]));
                zip.write(entries[i + 1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return archive;
    }

    private Path duplicateArchive() throws Exception {
        Path archive = Files.createTempFile(root, "project-", ".zip");
        try (OutputStream output = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("a/../file.txt"));
            zip.write('a');
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("file.txt"));
            zip.write('b');
            zip.closeEntry();
        }
        return archive;
    }
}
