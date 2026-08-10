package com.agent.sandbox.ast;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.lib.PersonIdent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AstServiceDiffTest {

    private static final String SOURCE = """
            package example;
            public class Sample {
                int value() {
                    return 1;
                }
            }
            """;

    private static final String VALID_DIFF = """
            diff --git a/src/main/java/example/Sample.java b/src/main/java/example/Sample.java
            --- a/src/main/java/example/Sample.java
            +++ b/src/main/java/example/Sample.java
            @@ -1,6 +1,6 @@
             package example;
             public class Sample {
                 int value() {
            -        return 1;
            +        return 2;
                 }
             }
            """;

    private static final String APPLY_PATCH_FORMAT = """
            *** Begin Patch
            *** Update File: src/main/java/example/Sample.java
            @@
             package example;
             public class Sample {
                 int value() {
            -        return 1;
            +        return 2;
                 }
             }
            *** End Patch
            """;

    @TempDir
    Path temporaryDirectory;

    @Test
    void appliesUnifiedDiffAndReturnsUpdatedFiles() throws Exception {
        Path repositoryRoot = initializeRepository("valid");
        Path sourceFile = writeSource(repositoryRoot);

        List<Path> updatedFiles = new AstService().applyDiff(repositoryRoot, VALID_DIFF);

        assertThat(Files.readString(sourceFile, StandardCharsets.UTF_8))
                .contains("return 2;")
                .doesNotContain("return 1;");
        assertThat(updatedFiles).containsExactly(sourceFile.toRealPath());
        assertThatThrownBy(() -> updatedFiles.add(sourceFile))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void acceptsModelApplyPatchFormatForExistingFile() throws Exception {
        Path repositoryRoot = initializeRepository("apply-patch-format");
        Path sourceFile = writeSource(repositoryRoot);

        new AstService().applyDiff(repositoryRoot, APPLY_PATCH_FORMAT);

        assertThat(Files.readString(sourceFile, StandardCharsets.UTF_8))
                .contains("return 2;")
                .doesNotContain("return 1;");
    }

    @Test
    void repairsModelUnifiedDiffHunkLineCounts() throws Exception {
        Path repositoryRoot = initializeRepository("malformed-hunk-counts");
        Path sourceFile = writeSource(repositoryRoot);
        String malformedCounts = VALID_DIFF.replace("@@ -1,6 +1,6 @@", "@@ -1,5 +1,5 @@");

        new AstService().applyDiff(repositoryRoot, malformedCounts);

        assertThat(Files.readString(sourceFile, StandardCharsets.UTF_8))
                .contains("return 2;")
                .doesNotContain("return 1;");
    }

    @Test
    void rejectsAmbiguousApplyPatchContextAndPreservesFile() throws Exception {
        Path repositoryRoot = initializeRepository("ambiguous-apply-patch");
        Path sourceFile = repositoryRoot.resolve("src/main/java/example/Sample.java");
        Files.createDirectories(sourceFile.getParent());
        String source = """
                package example;
                public class Sample {
                    int first() {
                        return 1;
                    }
                    int second() {
                        return 1;
                    }
                }
                """;
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
        String ambiguousPatch = """
                *** Begin Patch
                *** Update File: src/main/java/example/Sample.java
                @@
                -        return 1;
                +        return 2;
                *** End Patch
                """;

        assertThatThrownBy(() -> new AstService().applyDiff(repositoryRoot, ambiguousPatch))
                .isInstanceOf(AstServiceException.class)
                .hasMessageContaining("不唯一");
        assertThat(Files.readString(sourceFile, StandardCharsets.UTF_8)).isEqualTo(source);
    }

    @Test
    void rejectsUnsupportedApplyPatchDirectiveBeforeChangingFiles() throws Exception {
        Path repositoryRoot = initializeRepository("unsupported-apply-patch-directive");
        Path sourceFile = writeSource(repositoryRoot);
        String mixedPatch = APPLY_PATCH_FORMAT.replace(
                "*** End Patch",
                "*** Add File: extra.txt\n+extra\n*** End Patch");

        assertThatThrownBy(() -> new AstService().applyDiff(repositoryRoot, mixedPatch))
                .isInstanceOf(AstServiceException.class)
                .hasMessageContaining("不支持");
        assertThat(Files.readString(sourceFile, StandardCharsets.UTF_8)).isEqualTo(SOURCE);
        assertThat(repositoryRoot.resolve("extra.txt")).doesNotExist();
    }

    @Test
    void appliesModelPatchToCrLfFile() throws Exception {
        Path repositoryRoot = initializeRepository("crlf-apply-patch");
        Path sourceFile = repositoryRoot.resolve("src/main/java/example/Sample.java");
        Files.createDirectories(sourceFile.getParent());
        String crLfSource = SOURCE.replace("\n", "\r\n");
        Files.writeString(sourceFile, crLfSource, StandardCharsets.UTF_8);

        new AstService().applyDiff(repositoryRoot, APPLY_PATCH_FORMAT);

        String updated = Files.readString(sourceFile, StandardCharsets.UTF_8);
        assertThat(updated)
                .contains("return 2;")
                .doesNotContain("return 1;")
                .contains("\r\n");
    }

    @Test
    void rejectsConflictingPatchAndPreservesFile() throws Exception {
        Path repositoryRoot = initializeRepository("conflict");
        Path sourceFile = writeSource(repositoryRoot);
        AstService service = new AstService();
        service.applyDiff(repositoryRoot, VALID_DIFF);

        assertThatThrownBy(() -> service.applyDiff(repositoryRoot, VALID_DIFF))
                .isInstanceOf(AstServiceException.class)
                .hasCauseInstanceOf(Exception.class);
        assertThat(Files.readString(sourceFile, StandardCharsets.UTF_8))
                .contains("return 2;");
    }

    @Test
    void rejectsEmptyPatchAndNonRepository() throws IOException {
        Path plainDirectory = Files.createDirectory(temporaryDirectory.resolve("plain"));
        AstService service = new AstService();

        assertThatThrownBy(() -> service.applyDiff(plainDirectory, " "))
                .isInstanceOf(AstServiceException.class)
                .hasMessageContaining("补丁");
        assertThatThrownBy(() -> service.applyDiff(plainDirectory, VALID_DIFF))
                .isInstanceOf(AstServiceException.class)
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void validatesRepositoryBeforeParsingNonEmptyPatch() throws IOException {
        Path plainDirectory = Files.createDirectory(temporaryDirectory.resolve("invalid-patch"));

        assertThatThrownBy(() -> new AstService().applyDiff(plainDirectory, "not a diff"))
                .isInstanceOf(AstServiceException.class)
                .hasCauseInstanceOf(RepositoryNotFoundException.class);
    }

    @Test
    void rejectsPathTraversalBeforeWritingOutsideRepository() throws Exception {
        Path repositoryRoot = initializeRepository("traversal");
        Path outsideFile = repositoryRoot.getParent().resolve("outside.txt");
        String traversalDiff = """
                diff --git a/../outside.txt b/../outside.txt
                new file mode 100644
                --- /dev/null
                +++ b/../outside.txt
                @@ -0,0 +1 @@
                +outside
                """;

        assertThatThrownBy(() -> new AstService().applyDiff(repositoryRoot, traversalDiff))
                .isInstanceOf(AstServiceException.class)
                .hasMessageContaining("工作树");
        assertThat(outsideFile).doesNotExist();
    }

    @Test
    void preservesTrackedFileAfterApplyingDiff() throws Exception {
        Path repositoryRoot = initializeRepository("tracked-file");
        Path file = repositoryRoot.resolve("greeting.txt");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);
        try (Git git = Git.open(repositoryRoot.toFile())) {
            git.add().addFilepattern("greeting.txt").call();
            git.commit()
                    .setMessage("baseline")
                    .setAuthor(new PersonIdent("test", "test@example.com"))
                    .call();
        }
        Path index = repositoryRoot.resolve(".git/index");
        byte[] originalIndex = Files.readAllBytes(index);

        new AstService().applyDiff(repositoryRoot, """
                diff --git a/greeting.txt b/greeting.txt
                --- a/greeting.txt
                +++ b/greeting.txt
                @@ -1 +1 @@
                -hello
                +hello agent4j
                """);

        try (Git git = Git.open(repositoryRoot.toFile())) {
            var status = git.status().call();
            assertThat(status.getModified())
                    .containsExactly("greeting.txt");
            assertThat(status.getChanged())
                    .doesNotContain("greeting.txt");
            assertThat(status.getUntracked())
                    .doesNotContain("greeting.txt");
        }
        assertThat(Files.readAllBytes(index)).containsExactly(originalIndex);
    }

    private Path initializeRepository(String directoryName) throws Exception {
        Path repositoryRoot = Files.createDirectory(temporaryDirectory.resolve(directoryName));
        try (Git ignored = Git.init().setDirectory(repositoryRoot.toFile()).call()) {
            return repositoryRoot;
        }
    }

    private Path writeSource(Path repositoryRoot) throws IOException {
        Path sourceFile = repositoryRoot.resolve("src/main/java/example/Sample.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, SOURCE, StandardCharsets.UTF_8);
        return sourceFile;
    }
}
