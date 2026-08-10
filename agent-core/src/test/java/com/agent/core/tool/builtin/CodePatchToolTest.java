package com.agent.core.tool.builtin;

import com.agent.core.intent.RequiredCapability;
import com.agent.core.tool.DefaultToolRegistry;
import com.agent.core.tool.ToolCall;
import com.agent.core.tool.ToolInvocationContext;
import com.agent.core.tool.ToolResult;
import com.agent.core.tool.ToolResultStatus;
import com.agent.sandbox.ast.AstService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodePatchToolTest {

    private static final UUID RUN_ID = UUID.fromString(
            "a6cf0e7d-8e43-4c43-9f9e-bd94c4a8a0e6");

    @TempDir
    Path workspace;

    @Test
    void appliesDiffThroughGovernedRegistryAndReturnsRelativeFiles() throws Exception {
        initializeRepository("before\n");
        ObjectMapper mapper = new ObjectMapper();
        try (DefaultToolRegistry registry = new DefaultToolRegistry()) {
            registry.register(CodePatchTool.definition(new AstService(), mapper));
            ToolResult result = registry.execute(
                    new ToolCall(
                            "call-1",
                            CodePatchTool.NAME,
                            mapper.createObjectNode().put("unifiedDiff", validDiff())),
                    context());

            assertThat(result.status()).isEqualTo(ToolResultStatus.SUCCEEDED);
            assertThat(result.output().get("updatedFiles").toString())
                    .isEqualTo("[\"value.txt\"]");
            assertThat(Files.readString(workspace.resolve("value.txt"))).isEqualTo("after\n");
        }
    }

    @Test
    void returnsRenderableUnifiedDiffWhenModelUsesApplyPatchFormat() throws Exception {
        initializeRepository("before\n");
        ObjectMapper mapper = new ObjectMapper();
        try (DefaultToolRegistry registry = new DefaultToolRegistry()) {
            registry.register(CodePatchTool.definition(new AstService(), mapper));
            ToolResult result = registry.execute(
                    new ToolCall(
                            "call-apply-patch",
                            CodePatchTool.NAME,
                            mapper.createObjectNode().put("unifiedDiff", """
                                    *** Begin Patch
                                    *** Update File: value.txt
                                    @@
                                    -before
                                    +after
                                    *** End Patch
                                    """)),
                    context());

            assertThat(result.status()).isEqualTo(ToolResultStatus.SUCCEEDED);
            assertThat(result.output().path("unifiedDiff").asText())
                    .startsWith("diff --git a/value.txt b/value.txt\n")
                    .doesNotContain("*** Begin Patch");
        }
    }

    @Test
    void preservesConflictStackAndRejectsWorkspaceArguments() throws Exception {
        initializeRepository("before\n");
        ObjectMapper mapper = new ObjectMapper();
        try (DefaultToolRegistry registry = new DefaultToolRegistry()) {
            registry.register(CodePatchTool.definition(new AstService(), mapper));
            ToolResult conflict = registry.execute(
                    new ToolCall(
                            "call-2",
                            CodePatchTool.NAME,
                            mapper.createObjectNode().put("unifiedDiff", conflictingDiff())),
                    context());
            ToolResult extra = registry.execute(
                    new ToolCall(
                            "call-3",
                            CodePatchTool.NAME,
                            mapper.createObjectNode()
                                    .put("unifiedDiff", validDiff())
                                    .put("workspacePath", workspace.toString())),
                    context());

            assertThat(conflict.status()).isEqualTo(ToolResultStatus.FAILED);
            assertThat(conflict.errorStack())
                    .contains("com.agent.sandbox.ast.AstServiceException")
                    .contains("at ");
            assertThat(extra.status()).isEqualTo(ToolResultStatus.DENIED);
            assertThat(extra.errorStack()).contains("参数字段未声明");
            assertThat(Files.readString(workspace.resolve("value.txt"))).isEqualTo("before\n");
        }
    }

    private ToolInvocationContext context() {
        return new ToolInvocationContext(
                RUN_ID,
                "coder",
                "user",
                workspace,
                Set.of(RequiredCapability.CODE_WRITE),
                true);
    }

    private void initializeRepository(String content) throws Exception {
        Files.writeString(workspace.resolve("value.txt"), content);
        try (Git ignored = Git.init().setDirectory(workspace.toFile()).call()) {
        }
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
