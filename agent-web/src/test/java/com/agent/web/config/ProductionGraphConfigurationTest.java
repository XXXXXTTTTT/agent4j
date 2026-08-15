package com.agent.web.config;

import com.agent.core.engine.GraphFactory;
import com.agent.core.engine.AgentState;
import com.agent.core.engine.GraphRegistry;
import com.agent.core.engine.StateGraph;
import com.agent.core.llm.ModelRouter;
import com.agent.core.memory.MemoryContextProvider;
import com.agent.core.nodes.PlannerNode;
import com.agent.core.nodes.CoderNode;
import com.agent.core.nodes.ReviewerNode;
import com.agent.core.nodes.OpsNode;
import com.agent.core.trace.RunLogPublisher;
import com.agent.core.cli.WorkspaceTerminalTargetResolver;
import com.agent.core.cli.CliApprovalInterruptPolicy;
import com.agent.core.cli.CliCommandCatalog;
import com.agent.core.cli.CliCommandDefinition;
import com.agent.core.cli.CliRiskLevel;
import com.agent.core.harness.HarnessHookChain;
import com.agent.core.intent.RequiredCapability;
import com.agent.sandbox.ast.AstService;
import com.agent.sandbox.ast.WorkspaceSnapshotService;
import com.agent.sandbox.browser.BrowserAutomation;
import com.agent.sandbox.pty.DockerTarget;
import com.agent.sandbox.pty.PtyTarget;
import com.agent.sandbox.pty.SandboxTerminalService;
import com.agent.sandbox.pty.TerminalTarget;
import com.agent.sandbox.pty.CommandResult;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
            assertThat(graph.inspectTopology().nodeNames())
                    .contains("reviewer-failure");
            assertThat(graph.inspectTopology().outgoingTargets().get("reviewer"))
                    .contains("reviewer-failure");
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
    void registersParallelResearchAsPlannerConditionalTarget() throws Exception {
        try (var orchestrator = new com.agent.web.orchestration.ProductionMultiAgentOrchestrator(
                new com.agent.core.multiagent.AgentHandoffExecutor(
                        com.agent.web.orchestration.ProductionMultiAgentOrchestrator.catalog(),
                        new GraphRegistry(Map.of("unused", () -> {
                            StateGraph graph = new StateGraph(1);
                            graph.addNode("unused", state -> state)
                                    .setEntryPoint("unused")
                                    .addEdge("unused", StateGraph.END);
                            return graph;
                        })),
                        event -> { }))) {
            ProductionAgentProperties properties = properties("PTY", "", "");
            ObjectProvider<com.agent.web.orchestration.ProductionMultiAgentOrchestrator>
                    orchestratorProvider = mock(ObjectProvider.class);
            when(orchestratorProvider.getIfAvailable()).thenReturn(orchestrator);

            GraphFactory factory = new ProductionGraphConfiguration().codeAgentGraph(
                    properties,
                    new KnowledgeProperties(true, 4_000),
                    mock(ModelRouter.class),
                    request -> new com.agent.core.memory.MemoryContext("", 0),
                    com.agent.core.knowledge.KnowledgeContextProvider.empty(),
                    mock(SandboxTerminalService.class),
                    mock(BrowserAutomation.class),
                    new AstService(),
                    new WorkspaceSnapshotService(50, 32_000),
                    RunLogPublisher.noop(),
                    new com.fasterxml.jackson.databind.ObjectMapper(),
                    HarnessHookChain.noop(),
                    com.agent.core.security.SecurityViolationSink.noop(),
                    new com.agent.core.tool.DefaultToolRegistry(),
                    new ProductionGraphConfiguration().productionCliCommandCatalog(),
                    new ProductionGraphConfiguration().workspaceTargetResolver(properties),
                    mock(com.agent.core.gui.BrowserSessionRegistry.class),
                    orchestratorProvider);

            try (StateGraph graph = factory.create()) {
                assertThat(graph.inspectTopology().outgoingTargets().get("planner"))
                        .contains("orchestration-research");
            }
        }
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
    void routesRejectedReviewToFailureAfterRepairBudgetIsExhausted() {
        ProductionGraphConfiguration configuration = new ProductionGraphConfiguration();
        AgentState approved = AgentState.empty()
                .withVariable(ReviewerNode.APPROVED_KEY, "true")
                .withVariable(CoderNode.ATTEMPT_KEY, "1");
        AgentState repairable = AgentState.empty()
                .withVariable(ReviewerNode.APPROVED_KEY, "false")
                .withVariable(CoderNode.ATTEMPT_KEY, "1");
        AgentState rejected = AgentState.empty()
                .withVariable(ReviewerNode.APPROVED_KEY, "false")
                .withVariable(CoderNode.ATTEMPT_KEY, "2");

        assertThat(configuration.reviewerRoute(approved, 2))
                .isEqualTo("finish");
        assertThat(configuration.reviewerRoute(repairable, 2))
                .isEqualTo("repair");
        assertThat(configuration.reviewerRoute(rejected, 2))
                .isEqualTo("failure");
    }

    @Test
    void exposesUsefulProjectInspectionCommands() {
        var catalog = new ProductionGraphConfiguration().productionCliCommandCatalog();

        var maven = catalog.find("test.maven").orElseThrow();
        assertThat(maven.executable()).isEqualTo("mvn");
        assertThat(maven.fixedArguments()).containsExactly("test");
        var files = catalog.find("project.files").orElseThrow();
        assertThat(files.executable()).isEqualTo("find");
        assertThat(files.fixedArguments()).containsExactly(".", "-maxdepth", "2", "-type", "f");
        assertThat(catalog.find("mvn")).isEmpty();
    }

    @Test
    void createsGovernedCliGraphWithOnlyOpsAndEnd() throws Exception {
        Path bash = Files.createFile(workspace.resolve("governed-cli-bash.exe"));
        ProductionAgentProperties properties = properties("PTY", "", "");
        CliCommandCatalog catalog = new CliCommandCatalog(List.of(new CliCommandDefinition(
                "test.maven", "mvn", List.of("test"), CliRiskLevel.READ_ONLY,
                Set.of(RequiredCapability.TERMINAL))));
        CliApprovalInterruptPolicy policy = new CliApprovalInterruptPolicy(
                catalog,
                new PtyTarget(bash, workspace),
                Duration.ofSeconds(30),
                new com.fasterxml.jackson.databind.ObjectMapper());

        GraphFactory factory = new ProductionGraphConfiguration().governedCliGraph(
                properties,
                mock(SandboxTerminalService.class),
                RunLogPublisher.noop(),
                policy,
                HarnessHookChain.noop());

        try (StateGraph graph = factory.create()) {
            assertThat(graph.entryPoint()).isEqualTo("ops");
            assertThat(graph.inspectTopology().nodeNames()).containsExactly("ops");
            assertThat(graph.inspectTopology().outgoingTargets().get("ops"))
                    .containsExactly(StateGraph.END);
        }
    }

    @Test
    void codeAgentOpsDoesNotRequireGovernedCliTimeoutState() throws Exception {
        Path bash = Files.createFile(workspace.resolve("bash.exe"));
        AtomicReference<com.agent.sandbox.pty.CommandRequest> command = new AtomicReference<>();
        SandboxTerminalService terminal = mock(SandboxTerminalService.class);
        org.mockito.Mockito.when(terminal.execute(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    command.set(invocation.getArgument(0));
                    return CompletableFuture.completedFuture(new CommandResult(0, "ok", "", false));
                });
        ProductionGraphConfiguration configuration = new ProductionGraphConfiguration();
        GraphFactory factory = configuration.codeAgentGraph(
                properties("PTY", "", ""),
                mock(ModelRouter.class),
                request -> new com.agent.core.memory.MemoryContext("", 0),
                terminal,
                mock(BrowserAutomation.class),
                new AstService(),
                new WorkspaceSnapshotService(50, 32_000),
                RunLogPublisher.noop());

        AgentState result;
        try (StateGraph graph = factory.create()) {
            com.agent.core.engine.GraphExecutionResult execution = graph.execute(
                    new com.agent.core.engine.GraphExecutionRequest(
                    java.util.UUID.randomUUID(),
                    AgentState.empty()
                            .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString())
                            .withVariable(OpsNode.COMMAND_NAME_KEY, "test.maven")
                            .withVariable(OpsNode.COMMAND_ARGUMENTS_KEY, "[]")
                            .withVariable(PlannerNode.REQUIRED_CAPABILITIES_KEY, "TERMINAL"),
                    "ops", false), new com.agent.core.engine.GraphExecutionListener() {
                        @Override
                        public void onNodeStarted(String nodeName, AgentState state) {
                        }

                        @Override
                        public void onNodeCompleted(
                                String nodeName, String nextNode, AgentState state) {
                        }
                    });
            assertThat(execution).isInstanceOf(
                    com.agent.core.engine.GraphExecutionResult.Completed.class);
            result = ((com.agent.core.engine.GraphExecutionResult.Completed) execution).state();
        }

        assertThat(result.variables()).doesNotContainKey(OpsNode.ERROR_KEY);
        assertThat(command.get().bashCommand()).isEqualTo("'mvn' 'test'");
        assertThat(command.get().timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(command.get().target()).isEqualTo(new PtyTarget(bash, workspace.toRealPath()));
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
