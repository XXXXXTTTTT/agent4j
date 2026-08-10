package com.agent.web.config;

import com.agent.core.engine.GraphFactory;
import com.agent.core.engine.AgentState;
import com.agent.core.engine.StateGraph;
import com.agent.core.llm.ModelRouter;
import com.agent.core.memory.MemoryContextProvider;
import com.agent.core.nodes.PlannerNode;
import com.agent.core.trace.RunLogPublisher;
import com.agent.core.cli.WorkspaceTerminalTargetResolver;
import com.agent.sandbox.ast.AstService;
import com.agent.sandbox.ast.WorkspaceSnapshotService;
import com.agent.sandbox.browser.BrowserAutomation;
import com.agent.sandbox.pty.DockerTarget;
import com.agent.sandbox.pty.PtyTarget;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                1_800_000,
                120_000,
                200_000,
                3,
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

    @Test
    void acceptsOnlyExactPlannerRouteValues() {
        ProductionGraphConfiguration configuration = new ProductionGraphConfiguration();

        assertThat(configuration.plannerRoute(AgentState.empty()
                .withVariable(PlannerNode.ROUTE_KEY, PlannerNode.CHAT_ROUTE)))
                .isEqualTo(PlannerNode.CHAT_ROUTE);
        assertThat(configuration.plannerRoute(AgentState.empty()
                .withVariable(PlannerNode.ROUTE_KEY, PlannerNode.KNOWLEDGE_ROUTE)))
                .isEqualTo(PlannerNode.KNOWLEDGE_ROUTE);
        assertThat(configuration.plannerRoute(AgentState.empty()
                .withVariable(PlannerNode.ROUTE_KEY, PlannerNode.AGENT_ROUTE)))
                .isEqualTo(PlannerNode.AGENT_ROUTE);
        assertThat(configuration.plannerRoute(AgentState.empty()
                .withVariable(PlannerNode.ROUTE_KEY, PlannerNode.FAILED_ROUTE)))
                .isEqualTo(PlannerNode.FAILED_ROUTE);
        assertThatThrownBy(() -> configuration.plannerRoute(AgentState.empty()
                .withVariable(PlannerNode.ROUTE_KEY, "unexpected")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unexpected");
    }

    @Test
    void exposesNaturalMavenCommandNameToCodeModel() {
        var catalog = new ProductionGraphConfiguration().productionCliCommandCatalog();

        var maven = catalog.find("mvn").orElseThrow();
        assertThat(maven.executable()).isEqualTo("mvn");
        assertThat(maven.fixedArguments()).isEmpty();
    }

    @Test
    void resolvesPtyTargetFromEachRealWorkspaceDirectory() throws Exception {
        Path bash = Files.createFile(workspace.resolve("bash.exe"));
        Path first = Files.createDirectories(workspace.resolve("first"));
        Path second = Files.createDirectories(workspace.resolve("second"));
        ProductionAgentProperties properties = new ProductionAgentProperties(
                true,
                workspace,
                "repo",
                "user",
                "",
                "PTY",
                bash.toString(),
                "python:3.12-slim",
                "/workspace",
                "",
                "",
                Duration.ofSeconds(30),
                Duration.ofSeconds(15),
                50,
                32_000,
                2,
                12,
                1_800_000,
                120_000,
                200_000,
                3,
                12_000);

        WorkspaceTerminalTargetResolver resolver =
                new ProductionGraphConfiguration().workspaceTargetResolver(properties);

        assertThat(resolver.resolve(first))
                .isEqualTo(new PtyTarget(bash, first.toRealPath()));
        assertThat(resolver.resolve(second))
                .isEqualTo(new PtyTarget(bash, second.toRealPath()));
    }

    @Test
    void resolvesDockerHostTargetFromChildWorkspaceAndRejectsEscape() throws Exception {
        Path child = Files.createDirectories(workspace.resolve("modules").resolve("app"));
        ProductionAgentProperties properties = properties("DOCKER", "", "");
        WorkspaceTerminalTargetResolver resolver =
                new ProductionGraphConfiguration().workspaceTargetResolver(properties);

        assertThat(resolver.resolve(child)).isEqualTo(new DockerTarget(
                properties.dockerImage(),
                child.toRealPath(),
                properties.containerWorkspace()));
        Path outside = Files.createDirectories(workspace.getParent().resolve("outside-agent4j"));
        assertThatThrownBy(() -> resolver.resolve(outside))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolvesDockerContainerTargetWithExactRelativeWorkspacePath() throws Exception {
        Path child = Files.createDirectories(workspace.resolve("modules").resolve("app"));
        ProductionAgentProperties properties = properties(
                "DOCKER", "agent4j-web-local", "/agent-workspace");
        WorkspaceTerminalTargetResolver resolver =
                new ProductionGraphConfiguration().workspaceTargetResolver(properties);

        DockerTarget target = (DockerTarget) resolver.resolve(child);
        assertThat(target.hostWorkspace()).isEqualTo(child.toRealPath());
        assertThat(target.workspaceSource()).isEqualTo(
                new DockerTarget.ContainerWorkspaceSource(
                        "agent4j-web-local", "/agent-workspace", "modules/app"));
    }

    private ProductionAgentProperties properties(
            String mode,
            String sourceContainer,
            String sourcePath) {
        return new ProductionAgentProperties(
                true,
                workspace,
                "repo",
                "user",
                "",
                mode,
                workspace.resolve("bash.exe").toString(),
                "python:3.12-slim",
                "/workspace",
                sourceContainer,
                sourcePath,
                Duration.ofSeconds(30),
                Duration.ofSeconds(15),
                50,
                32_000,
                2,
                12,
                1_800_000,
                120_000,
                200_000,
                3,
                12_000);
    }
}
