package com.agent.core.nodes;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.StateGraph;
import com.agent.sandbox.ast.AstService;
import com.agent.sandbox.pty.CommandResult;
import com.agent.sandbox.pty.PtyTarget;
import com.agent.sandbox.pty.TerminalCommandExecutor;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class CoderOpsGraphTest {

    @TempDir
    Path workspace;

    @Test
    void appliesDiffThenRunsCommandOnVirtualThreads() throws Exception {
        Files.writeString(workspace.resolve("value.txt"), "before\n");
        try (Git ignored = Git.init().setDirectory(workspace.toFile()).call()) {
            // 测试只需要真实 Git 工作树，不创建提交。
        }
        Path bashExecutable = Files.createFile(workspace.resolve("bash.exe"));
        PtyTarget target = new PtyTarget(bashExecutable, workspace);
        CoderNode coderNode = new CoderNode(new AstService());
        TerminalCommandExecutor terminal = (request, logConsumer) -> {
            assertThat(request.target()).isSameAs(target);
            assertThat(request.bashCommand()).isEqualTo("cat value.txt");
            try {
                return CompletableFuture.completedFuture(new CommandResult(
                        0,
                        Files.readString(workspace.resolve("value.txt")).strip(),
                        "",
                        false));
            } catch (IOException exception) {
                return CompletableFuture.failedFuture(exception);
            }
        };
        OpsNode opsNode = new OpsNode(terminal, target, Duration.ofSeconds(30));

        try (StateGraph graph = new StateGraph(4)) {
            graph.addNode("coder", state -> coderNode.execute(state)
                            .withVariable("test.coderVirtual",
                                    Boolean.toString(Thread.currentThread().isVirtual())))
                    .addNode("ops", state -> opsNode.execute(state)
                            .withVariable("test.opsVirtual",
                                    Boolean.toString(Thread.currentThread().isVirtual())))
                    .addEdge("coder", "ops")
                    .addEdge("ops", StateGraph.END)
                    .setEntryPoint("coder");

            AgentState result = graph.execute(AgentState.empty()
                    .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString())
                    .withVariable(CoderNode.UNIFIED_DIFF_KEY, validDiff())
                    .withVariable(OpsNode.COMMAND_KEY, "cat value.txt"));

            assertThat(result.variables())
                    .containsEntry(OpsNode.STDOUT_KEY, "after")
                    .containsEntry("test.coderVirtual", "true")
                    .containsEntry("test.opsVirtual", "true");
            assertThat(result.trace()).containsExactly("coder", "ops");
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
}
