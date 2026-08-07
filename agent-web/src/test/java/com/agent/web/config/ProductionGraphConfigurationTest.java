package com.agent.web.config;

import com.agent.core.engine.GraphFactory;
import com.agent.core.engine.StateGraph;
import com.agent.core.llm.ModelRouter;
import com.agent.core.memory.MemoryContextProvider;
import com.agent.core.trace.RunLogPublisher;
import com.agent.sandbox.ast.AstService;
import com.agent.sandbox.ast.WorkspaceSnapshotService;
import com.agent.sandbox.browser.BrowserAutomation;
import com.agent.sandbox.pty.DockerTarget;
import com.agent.sandbox.pty.SandboxTerminalService;
import com.agent.sandbox.pty.TerminalTarget;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.mockito.Mockito.mock;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionGraphConfigurationTest {

    @TempDir
    Path workspace;

    @Test
    void createsCodeAgentGraphWithPlannerEntryAndRepairRoute() throws Exception {
        try (Git ignored = Git.init().setDirectory(workspace.toFile()).call()) {
            Files.writeString(workspace.resolve("value.txt"), "before\n");
        }
        ProductionAgentProperties properties = new ProductionAgentProperties(
                true,
                workspace,
                "repo",
                "user",
                "",
                "DOCKER",
                "/bin/bash",
                "python:3.12-slim",
                "/workspace",
                "agent4j-web-local",
                "/agent-workspace",
                Duration.ofSeconds(30),
                Duration.ofSeconds(15),
                50,
                32_000,
                2,
                12,
                12_000);

        GraphFactory factory = new ProductionGraphConfiguration().codeAgentGraph(
                properties,
                mock(ModelRouter.class),
                request -> new com.agent.core.memory.MemoryContext("", 0),
                mock(SandboxTerminalService.class),
                mock(BrowserAutomation.class),
                new AstService(),
                new WorkspaceSnapshotService(50, 32_000),
                RunLogPublisher.noop());

        try (StateGraph graph = factory.create()) {
            assertThat(graph.entryPoint()).isEqualTo("planner");
        }

        TerminalTarget terminalTarget =
                new ProductionGraphConfiguration().terminalTarget(properties);
        assertThat(terminalTarget).isEqualTo(new DockerTarget(
                "python:3.12-slim",
                workspace,
                "/workspace",
                new DockerTarget.ContainerWorkspaceSource(
                        "agent4j-web-local", "/agent-workspace")));
    }
}
