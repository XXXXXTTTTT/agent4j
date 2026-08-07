package com.agent.core.knowledge;

import com.agent.core.intent.TaskComplexity;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeContextTest {

    @Test
    void validatesEvidenceStatusAndPreservesDegradedStack() {
        KnowledgeEvidence applied = new KnowledgeEvidence(
                KnowledgeEvidenceKind.PROJECT_FILE,
                "AGENTS.md",
                KnowledgeEvidenceStatus.APPLIED,
                "已加载项目规则",
                null);

        assertThat(applied.errorStack()).isNull();
        assertThatThrownBy(() -> new KnowledgeEvidence(
                KnowledgeEvidenceKind.PROJECT_FILE,
                "AGENTS.md",
                KnowledgeEvidenceStatus.APPLIED,
                "已加载项目规则",
                "java.lang.IllegalStateException"))
                .isInstanceOf(IllegalArgumentException.class);

        KnowledgeEvidence degraded = new KnowledgeEvidence(
                KnowledgeEvidenceKind.RAG_STAGE,
                "RAG_PIPELINE",
                KnowledgeEvidenceStatus.DEGRADED,
                "RAG 失败，保留项目规则",
                "java.lang.IllegalStateException: backend unavailable\n\tat test.Callable.run(Callable.java:1)");

        assertThat(degraded.errorStack()).contains("backend unavailable");
        assertThatThrownBy(() -> new KnowledgeEvidence(
                KnowledgeEvidenceKind.RAG_STAGE,
                "RAG_PIPELINE",
                KnowledgeEvidenceStatus.DEGRADED,
                "RAG 失败",
                null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizesRequestPathsAndRejectsActivePathOutsideWorkspace() {
        Path workspace = Path.of(".", "project");
        Path active = workspace.resolve("src").resolve("..").resolve("Main.java");

        KnowledgeContextRequest request = new KnowledgeContextRequest(
                "repo",
                "user",
                workspace,
                active,
                "find the entry point",
                TaskComplexity.STANDARD,
                1_024);

        assertThat(request.workspaceRoot()).isEqualTo(workspace.toAbsolutePath().normalize());
        assertThat(request.activePath()).isEqualTo(active.toAbsolutePath().normalize());
        assertThat(request.activePath().startsWith(request.workspaceRoot())).isTrue();

        assertThatThrownBy(() -> new KnowledgeContextRequest(
                "repo",
                "user",
                workspace,
                workspace.resolve("..").resolve("outside"),
                "query",
                TaskComplexity.SIMPLE,
                128))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void freezesEvidenceAndRequiresDegradedContextConsistency() {
        List<KnowledgeEvidence> evidence = new ArrayList<>();
        evidence.add(new KnowledgeEvidence(
                KnowledgeEvidenceKind.PROJECT_FILE,
                "AGENTS.md",
                KnowledgeEvidenceStatus.APPLIED,
                "已加载",
                null));
        KnowledgeContext context = new KnowledgeContext(
                "规则",
                1,
                "fingerprint",
                3,
                false,
                evidence);

        evidence.add(new KnowledgeEvidence(
                KnowledgeEvidenceKind.PROJECT_FILE,
                "SOUL.md",
                KnowledgeEvidenceStatus.SKIPPED,
                "超出预算",
                null));
        assertThat(context.evidence()).hasSize(1);
        assertThatThrownBy(() -> context.evidence().add(evidence.get(1)))
                .isInstanceOf(UnsupportedOperationException.class);

        KnowledgeEvidence degraded = new KnowledgeEvidence(
                KnowledgeEvidenceKind.RAG_STAGE,
                "RAG_PIPELINE",
                KnowledgeEvidenceStatus.DEGRADED,
                "降级",
                "stack");
        assertThatThrownBy(() -> new KnowledgeContext(
                "规则",
                0,
                "fingerprint",
                1,
                true,
                List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new KnowledgeContext(
                "规则",
                1,
                "fingerprint",
                1,
                true,
                List.of(degraded)).degraded()).isTrue();
    }

    @Test
    void emptyProviderReturnsDeterministicEmptyContext() {
        KnowledgeContextRequest request = new KnowledgeContextRequest(
                "repo",
                "user",
                Path.of("."),
                Path.of("."),
                "query",
                TaskComplexity.SIMPLE,
                128);

        KnowledgeContext first = KnowledgeContextProvider.empty().load(request);
        KnowledgeContext second = KnowledgeContextProvider.empty().load(request);

        assertThat(first).isSameAs(second);
        assertThat(first).isEqualTo(KnowledgeContext.empty());
        assertThatThrownBy(() -> KnowledgeContextProvider.empty().load(null))
                .isInstanceOf(NullPointerException.class);
    }
}
