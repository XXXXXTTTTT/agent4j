package com.agent.rag.knowledge;

import com.agent.core.context.Utf8TokenEstimator;
import com.agent.core.intent.TaskComplexity;
import com.agent.core.knowledge.KnowledgeContext;
import com.agent.core.knowledge.KnowledgeContextRequest;
import com.agent.core.knowledge.KnowledgeEvidenceKind;
import com.agent.core.knowledge.KnowledgeEvidenceStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectFileKnowledgeContextProviderTest {

    @TempDir
    Path workspace;

    @Test
    void loadsProjectFilesWithoutRagStageEvidence() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "always run tests", StandardCharsets.UTF_8);
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/CLAUDE.md"), "prefer small changes", StandardCharsets.UTF_8);
        ProjectKnowledgeCompiler compiler = new ProjectKnowledgeCompiler(new Utf8TokenEstimator());
        ProjectKnowledgeContext expected = compiler.compile(workspace, workspace, 1_000);

        KnowledgeContext context = new ProjectFileKnowledgeContextProvider(
                compiler, new Utf8TokenEstimator()).load(new KnowledgeContextRequest(
                        "repo-a", "user-a", workspace, workspace,
                        "what are the project rules", TaskComplexity.SIMPLE, 1_000));

        assertThat(context.prompt()).isEqualTo(expected.prompt());
        assertThat(context.sourceCount()).isEqualTo(expected.sources().size());
        assertThat(context.fingerprint()).isEqualTo(expected.fingerprint());
        assertThat(context.estimatedTokens()).isEqualTo(expected.estimatedTokens());
        assertThat(context.degraded()).isFalse();
        assertThat(context.evidence())
                .hasSize(expected.sources().size())
                .allSatisfy(evidence -> {
                    assertThat(evidence.kind()).isEqualTo(KnowledgeEvidenceKind.PROJECT_FILE);
                    assertThat(evidence.status()).isEqualTo(KnowledgeEvidenceStatus.APPLIED);
                    assertThat(evidence.errorStack()).isNull();
                });
        assertThat(context.evidence()).noneMatch(
                evidence -> evidence.kind() == KnowledgeEvidenceKind.RAG_STAGE);
    }

    @Test
    void returnsEmptyKnowledgeContextWhenProjectHasNoRuleFiles() {
        ProjectKnowledgeCompiler compiler = new ProjectKnowledgeCompiler(new Utf8TokenEstimator());

        KnowledgeContext context = new ProjectFileKnowledgeContextProvider(
                compiler, new Utf8TokenEstimator()).load(new KnowledgeContextRequest(
                        "repo-empty", "user-a", workspace, workspace,
                        "explain this project", TaskComplexity.SIMPLE, 1_000));

        assertThat(context.prompt()).isEmpty();
        assertThat(context.sourceCount()).isZero();
        assertThat(context.fingerprint()).isEmpty();
        assertThat(context.estimatedTokens()).isZero();
        assertThat(context.degraded()).isFalse();
        assertThat(context.evidence()).isEmpty();
    }
}
