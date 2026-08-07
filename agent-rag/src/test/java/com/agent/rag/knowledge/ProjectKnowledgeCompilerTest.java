package com.agent.rag.knowledge;

import com.agent.core.context.TokenEstimator;
import com.agent.core.context.Utf8TokenEstimator;
import com.agent.core.llm.ChatMessage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectKnowledgeCompilerTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsExactKnowledgeFilesInRootToActiveOrder() throws IOException {
        Path root = Files.createDirectory(tempDir.resolve("repo"));
        Path source = Files.createDirectories(root.resolve("src/pkg"));
        Path activeFile = Files.writeString(source.resolve("Main.java"), "class Main {}", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("SOUL.md"), "soul-root", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("AGENTS.md"), "agents-root", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("CLAUDE.md"), "claude-root", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("src/AGENTS.md"), "agents-src", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("src/CLAUDE.md"), "claude-src", StandardCharsets.UTF_8);
        Files.writeString(source.resolve("AGENTS.md"), "agents-pkg", StandardCharsets.UTF_8);
        Files.writeString(source.resolve("CLAUDE.md"), "claude-pkg", StandardCharsets.UTF_8);
        Path unrelated = Files.createDirectories(root.resolve("other"));
        Files.writeString(unrelated.resolve("agents.md"), "must-ignore", StandardCharsets.UTF_8);

        ProjectKnowledgeContext context = compiler().compile(root, activeFile, 10_000);

        assertThat(context.sources()).extracting(KnowledgeSource::relativePath)
                .containsExactly(
                        "SOUL.md",
                        "AGENTS.md",
                        "src/AGENTS.md",
                        "src/pkg/AGENTS.md",
                        "CLAUDE.md",
                        "src/CLAUDE.md",
                        "src/pkg/CLAUDE.md");
        assertThat(context.prompt()).doesNotContain("must-ignore");
        assertThat(context.prompt().indexOf("soul-root"))
                .isLessThan(context.prompt().indexOf("agents-root"));
        assertThat(context.prompt().indexOf("agents-pkg"))
                .isLessThan(context.prompt().indexOf("claude-root"));

        ProjectKnowledgeContext directoryContext = compiler().compile(root, source, 10_000);
        assertThat(directoryContext.sources()).extracting(KnowledgeSource::relativePath)
                .containsExactlyElementsOf(context.sources().stream()
                        .map(KnowledgeSource::relativePath)
                        .toList());
    }

    @Test
    void rejectsRealActivePathOutsideWorkspace() throws IOException {
        Path root = Files.createDirectory(tempDir.resolve("repo"));
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "outside", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> compiler().compile(root, outside, 1_000))
                .isInstanceOf(ProjectKnowledgeException.class)
                .hasMessageContaining("activePath");
    }

    @Test
    void requiresRootAgentsAsTheMandatoryProjectRule() throws IOException {
        Path root = Files.createDirectory(tempDir.resolve("missing-root-agents"));
        Files.writeString(root.resolve("SOUL.md"), "optional", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> compiler().compile(root, root, 1_000))
                .isInstanceOf(ProjectKnowledgeException.class)
                .hasMessageContaining("AGENTS.md");
    }

    @Test
    void rejectsInvalidUtf8AndPreservesDecoderCause() throws IOException {
        Path root = Files.createDirectory(tempDir.resolve("repo"));
        Files.write(root.resolve("AGENTS.md"), new byte[] {(byte) 0xC3, 0x28});

        assertThatThrownBy(() -> compiler().compile(root, root, 1_000))
                .isInstanceOf(ProjectKnowledgeException.class)
                .hasCauseInstanceOf(CharacterCodingException.class);
    }

    @Test
    void reportsExactByteAndLineLimitValues() throws IOException {
        Path byteRoot = Files.createDirectory(tempDir.resolve("byte-repo"));
        Files.write(byteRoot.resolve("AGENTS.md"), new byte[25_001]);

        assertThatThrownBy(() -> compiler().compile(byteRoot, byteRoot, 100_000))
                .isInstanceOfSatisfying(ProjectKnowledgeLimitException.class, exception -> {
                    assertThat(exception.relativePath()).isEqualTo("AGENTS.md");
                    assertThat(exception.kind()).isEqualTo(ProjectKnowledgeLimitKind.BYTES);
                    assertThat(exception.observed()).isEqualTo(25_001);
                    assertThat(exception.limit()).isEqualTo(25_000);
                });

        Path lineRoot = Files.createDirectory(tempDir.resolve("line-repo"));
        String content = String.join("\n", java.util.Collections.nCopies(201, "line"));
        Files.writeString(lineRoot.resolve("AGENTS.md"), content, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> compiler().compile(lineRoot, lineRoot, 100_000))
                .isInstanceOfSatisfying(ProjectKnowledgeLimitException.class, exception -> {
                    assertThat(exception.relativePath()).isEqualTo("AGENTS.md");
                    assertThat(exception.kind()).isEqualTo(ProjectKnowledgeLimitKind.LINES);
                    assertThat(exception.observed()).isEqualTo(201);
                    assertThat(exception.limit()).isEqualTo(200);
                });
    }

    @Test
    void rejectsKnowledgeFileSymlinkWhoseTargetLeavesWorkspace() throws IOException {
        Path root = Files.createDirectory(tempDir.resolve("repo"));
        Path outside = Files.writeString(tempDir.resolve("outside.md"), "outside", StandardCharsets.UTF_8);
        Path link = root.resolve("AGENTS.md");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException exception) {
            Assumptions.assumeTrue(false, "当前环境不允许创建符号链接: " + exception.getMessage());
        }

        assertThatThrownBy(() -> compiler().compile(root, root, 1_000))
                .isInstanceOf(ProjectKnowledgeException.class)
                .hasMessageContaining("工作区外");
    }

    @Test
    void keepsRootAgentsWithinBudgetAndSelectsOnlyCompleteOptionalSources() throws IOException {
        Path root = Files.createDirectory(tempDir.resolve("budget-repo"));
        Files.writeString(root.resolve("SOUL.md"), "soul", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("AGENTS.md"), "agents", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("CLAUDE.md"), "claude", StandardCharsets.UTF_8);

        TokenEstimator estimator = message -> {
            String text = ((ChatMessage.TextContent) message.content()).text();
            long sections = text.lines().filter(line -> line.startsWith("### [")).count();
            if (sections == 1) {
                return 4;
            }
            if (sections == 2) {
                return 7;
            }
            return 20;
        };

        ProjectKnowledgeContext context = new ProjectKnowledgeCompiler(estimator)
                .compile(root, root, 7);

        assertThat(context.sources()).extracting(KnowledgeSource::fileType)
                .containsExactly(KnowledgeFileType.SOUL, KnowledgeFileType.AGENTS);
        assertThat(context.prompt()).contains("soul", "agents").doesNotContain("claude");
        assertThat(context.estimatedTokens()).isEqualTo(7);
    }

    @Test
    void rejectsRootAgentsWhenItsCompleteSourceExceedsBudget() throws IOException {
        Path root = Files.createDirectory(tempDir.resolve("mandatory-repo"));
        Files.writeString(root.resolve("AGENTS.md"), "agents", StandardCharsets.UTF_8);

        TokenEstimator estimator = message -> 20;

        assertThatThrownBy(() -> new ProjectKnowledgeCompiler(estimator).compile(root, root, 10))
                .isInstanceOfSatisfying(ProjectKnowledgeLimitException.class, exception -> {
                    assertThat(exception.relativePath()).isEqualTo("AGENTS.md");
                    assertThat(exception.kind()).isEqualTo(ProjectKnowledgeLimitKind.TOKENS);
                    assertThat(exception.observed()).isEqualTo(20);
                    assertThat(exception.limit()).isEqualTo(10);
                });
    }

    @Test
    void cachesByContentFingerprintEvenWhenMtimeIsRestored() throws IOException {
        Path root = Files.createDirectory(tempDir.resolve("cache-repo"));
        Path agents = Files.writeString(root.resolve("AGENTS.md"), "version-one", StandardCharsets.UTF_8);
        ProjectKnowledgeCompiler compiler = compiler();

        ProjectKnowledgeContext first = compiler.compile(root, root, 1_000);
        ProjectKnowledgeContext same = compiler.compile(root, root, 1_000);
        assertThat(same).isSameAs(first);

        FileTime originalTime = Files.getLastModifiedTime(agents);
        Files.writeString(agents, "version-two", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(agents, originalTime);

        ProjectKnowledgeContext changed = compiler.compile(root, root, 1_000);
        assertThat(changed).isNotSameAs(first);
        assertThat(changed.fingerprint()).isNotEqualTo(first.fingerprint());
    }

    @Test
    void cacheKeyUsesExactActivePathEvenWhenFilesShareOneDirectory() throws IOException {
        Path root = Files.createDirectory(tempDir.resolve("active-path-cache"));
        Path firstFile = Files.writeString(root.resolve("First.java"), "class First {}", StandardCharsets.UTF_8);
        Path secondFile = Files.writeString(root.resolve("Second.java"), "class Second {}", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("AGENTS.md"), "rules", StandardCharsets.UTF_8);
        ProjectKnowledgeCompiler compiler = compiler();

        ProjectKnowledgeContext first = compiler.compile(root, firstFile, 1_000);
        ProjectKnowledgeContext second = compiler.compile(root, secondFile, 1_000);

        assertThat(second).isNotSameAs(first);
        assertThat(second).isEqualTo(first);
    }

    private ProjectKnowledgeCompiler compiler() {
        return new ProjectKnowledgeCompiler(new Utf8TokenEstimator());
    }
}
