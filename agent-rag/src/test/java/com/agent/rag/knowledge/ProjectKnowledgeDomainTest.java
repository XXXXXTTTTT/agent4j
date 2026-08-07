package com.agent.rag.knowledge;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectKnowledgeDomainTest {

    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void mapsOnlyExactKnowledgeFileNames() {
        assertThat(KnowledgeFileType.fromFileName("SOUL.md")).isEqualTo(KnowledgeFileType.SOUL);
        assertThat(KnowledgeFileType.fromFileName("AGENTS.md")).isEqualTo(KnowledgeFileType.AGENTS);
        assertThat(KnowledgeFileType.fromFileName("CLAUDE.md")).isEqualTo(KnowledgeFileType.CLAUDE);
        assertThat(KnowledgeFileType.fromFileName("agents.md")).isNull();
        assertThat(KnowledgeFileType.fromFileName("AGENTS.MD")).isNull();
    }

    @Test
    void validatesBoundedKnowledgeSourceMetadata() {
        KnowledgeSource source = new KnowledgeSource("docs/AGENTS.md", KnowledgeFileType.AGENTS, 2, 25_000, 200, HASH);

        assertThat(source.relativePath()).isEqualTo("docs/AGENTS.md");
        assertThat(source.depth()).isEqualTo(2);

        assertThatThrownBy(() -> new KnowledgeSource("C:/AGENTS.md", KnowledgeFileType.AGENTS, 0, 10, 1, HASH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeSource("../AGENTS.md", KnowledgeFileType.AGENTS, 0, 10, 1, HASH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeSource("AGENTS.md", KnowledgeFileType.AGENTS, -1, 10, 1, HASH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeSource("AGENTS.md", KnowledgeFileType.AGENTS, 0, 25_001, 1, HASH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeSource("AGENTS.md", KnowledgeFileType.AGENTS, 0, 10, 201, HASH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeSource("AGENTS.md", KnowledgeFileType.AGENTS, 0, 10, 1, HASH.substring(0, 63)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeSource("AGENTS.md", KnowledgeFileType.AGENTS, 0, 10, 1, HASH.toUpperCase()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void freezesProjectKnowledgeContextAndValidatesMetadata() {
        KnowledgeSource source = new KnowledgeSource("AGENTS.md", KnowledgeFileType.AGENTS, 0, 10, 1, HASH);
        List<KnowledgeSource> sources = new ArrayList<>(List.of(source));
        ProjectKnowledgeContext context = new ProjectKnowledgeContext("### [AGENTS]\n规则", sources, HASH, 4);

        sources.clear();
        assertThat(context.sources()).containsExactly(source);
        assertThatThrownBy(() -> context.sources().clear())
                .isInstanceOf(UnsupportedOperationException.class);

        assertThat(ProjectKnowledgeContext.empty()).isEqualTo(
                new ProjectKnowledgeContext("", List.of(), "", 0));
        assertThatThrownBy(() -> new ProjectKnowledgeContext("", List.of(source), HASH, 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProjectKnowledgeContext("prompt", List.of(), "", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesCausesAndExactLimitValues() {
        IllegalStateException cause = new IllegalStateException("读取失败");
        ProjectKnowledgeException exception = new ProjectKnowledgeException("知识文件读取失败", cause);
        assertThat(exception.getCause()).isSameAs(cause);

        ProjectKnowledgeLimitException limit = new ProjectKnowledgeLimitException(
                "AGENTS.md", ProjectKnowledgeLimitKind.BYTES, 25_001, 25_000);
        assertThat(limit.relativePath()).isEqualTo("AGENTS.md");
        assertThat(limit.kind()).isEqualTo(ProjectKnowledgeLimitKind.BYTES);
        assertThat(limit.observed()).isEqualTo(25_001);
        assertThat(limit.limit()).isEqualTo(25_000);
        assertThat(limit.getMessage()).contains("25,001", "25,000");
    }
}
