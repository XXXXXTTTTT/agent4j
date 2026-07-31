package com.agent.core.nodes;

import com.agent.core.engine.AgentState;
import com.agent.sandbox.ast.AstService;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoderNodeTest {

    @TempDir
    Path workspace;

    @BeforeEach
    void initializeRepository() throws Exception {
        Files.writeString(workspace.resolve("value.txt"), "before\n");
        try (Git ignored = Git.init().setDirectory(workspace.toFile()).call()) {
            // 测试只需要真实 Git 工作树，不创建提交。
        }
    }

    @Test
    void appliesDiffAndReturnsNewImmutableState() throws Exception {
        CoderNode node = new CoderNode(new AstService());
        AgentState original = AgentState.empty()
                .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString())
                .withVariable(CoderNode.UNIFIED_DIFF_KEY, validDiff());

        AgentState result = node.execute(original);

        assertThat(result).isNotSameAs(original);
        assertThat(original.variables()).doesNotContainKey(CoderNode.UPDATED_FILES_KEY);
        assertThat(original.trace()).isEmpty();
        assertThat(Files.readString(workspace.resolve("value.txt"))).isEqualTo("after\n");
        assertThat(result.variables())
                .containsEntry(CoderNode.UPDATED_FILES_KEY, "value.txt")
                .doesNotContainKey(CoderNode.ERROR_KEY);
        assertThat(result.trace()).containsExactly("coder");
    }

    @Test
    void recordsFullStackWhenWorkspacePathIsMissing() throws Exception {
        CoderNode node = new CoderNode(new AstService());
        AgentState result = node.execute(AgentState.empty()
                .withVariable(CoderNode.UNIFIED_DIFF_KEY, validDiff()));

        assertStackTrace(result, CoderNode.WORKSPACE_PATH_KEY);
        assertThat(result.variables()).doesNotContainKey(CoderNode.UPDATED_FILES_KEY);
        assertThat(result.trace()).containsExactly("coder");
    }

    @Test
    void recordsFullStackWhenUnifiedDiffIsMissing() throws Exception {
        CoderNode node = new CoderNode(new AstService());
        AgentState result = node.execute(AgentState.empty()
                .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString()));

        assertStackTrace(result, CoderNode.UNIFIED_DIFF_KEY);
        assertThat(result.variables()).doesNotContainKey(CoderNode.UPDATED_FILES_KEY);
        assertThat(result.trace()).containsExactly("coder");
    }

    @Test
    void recordsFullStackWhenDiffConflicts() throws Exception {
        CoderNode node = new CoderNode(new AstService());
        AgentState result = node.execute(AgentState.empty()
                .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString())
                .withVariable(CoderNode.UNIFIED_DIFF_KEY, conflictingDiff()));

        assertThat(result.variables().get(CoderNode.ERROR_KEY))
                .contains("com.agent.sandbox.ast.AstServiceException")
                .contains("at ");
        assertThat(result.variables()).doesNotContainKey(CoderNode.UPDATED_FILES_KEY);
        assertThat(Files.readString(workspace.resolve("value.txt"))).isEqualTo("before\n");
        assertThat(result.trace()).containsExactly("coder");
    }

    @Test
    void rejectsNullAstService() {
        assertThatThrownBy(() -> new CoderNode(null))
                .isInstanceOf(NullPointerException.class);
    }

    private void assertStackTrace(AgentState result, String missingKey) {
        assertThat(result.variables().get(CoderNode.ERROR_KEY))
                .contains("java.lang.IllegalArgumentException")
                .contains(missingKey)
                .contains("at ");
    }

    private String validDiff() {
        return """
                diff --git a/value.txt b/value.txt
                --- a/value.txt
                +++ b/value.txt
                @@ -1 +1 @@
                -before
                +after
                """;
    }

    private String conflictingDiff() {
        return """
                diff --git a/value.txt b/value.txt
                --- a/value.txt
                +++ b/value.txt
                @@ -1 +1 @@
                -missing
                +after
                """;
    }
}
