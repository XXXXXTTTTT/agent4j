package com.agent.eval;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.ExecutionBudget;
import com.agent.core.engine.ExecutionBudgetExceededException;
import com.agent.core.engine.ExecutionStopReason;
import com.agent.core.engine.GraphExecutionListener;
import com.agent.core.engine.GraphExecutionResult;
import com.agent.core.engine.GraphTopology;
import com.agent.core.engine.GraphTopologyException;
import com.agent.core.engine.InterruptRequest;
import com.agent.core.engine.InterruptPolicy;
import com.agent.core.engine.StateGraph;
import com.agent.core.engine.SubgraphInterruptedException;
import com.agent.core.engine.SubgraphNode;
import com.agent.core.engine.SubgraphStateBridge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 对 StateGraph 拓扑、组合和预算语义执行确定性工程 EDD。 */
class StateGraphCompositionEddTest {

    private static final Set<String> REPORT_FIELDS = Set.of(
            "taskId", "status", "valid", "unreachableNodes", "deadEndNodes",
            "nodesWithoutEndPath", "cyclicNodes", "stopReason", "passed");
    private static final List<String> TASK_IDS = List.of(
            "graph.linear", "graph.react-cycle", "graph.unreachable", "graph.dead-end",
            "graph.no-end-path", "graph.subgraph-bridge", "graph.subgraph-interrupt",
            "graph.loop-budget");

    @Test
    void evaluatesCompositionScenariosAndWritesAuditableReport() throws Exception {
        List<EddResult> results = List.of(
                linear(),
                reactCycle(),
                invalid("graph.unreachable", unreachableGraph()),
                invalid("graph.dead-end", deadEndGraph()),
                invalid("graph.no-end-path", noEndPathGraph()),
                subgraphBridge(),
                subgraphInterrupt(),
                loopBudget());

        Path report = Path.of("target", "edd", "state-graph-composition-edd.json");
        Files.createDirectories(report.getParent());
        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), Map.of(
                "scenarios", results));

        JsonNode reportJson = mapper.readTree(report.toFile());
        assertThat(results).extracting(EddResult::taskId).containsExactlyElementsOf(TASK_IDS);
        assertThat(reportJson.path("scenarios")).hasSize(TASK_IDS.size());
        for (JsonNode scenario : reportJson.path("scenarios")) {
            Set<String> fields = new LinkedHashSet<>();
            scenario.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrderElementsOf(REPORT_FIELDS);
            assertThat(scenario.path("taskId").asText()).isIn(TASK_IDS);
            assertThat(scenario.path("passed").asBoolean()).isTrue();
        }
        assertThat(report).isRegularFile();
    }

    private EddResult linear() {
        try (StateGraph graph = new StateGraph(3)) {
            graph.addNode("start", state -> state)
                    .addNode("finish", state -> state)
                    .addEdge("start", "finish")
                    .addEdge("finish", StateGraph.END)
                    .setEntryPoint("start");
            GraphTopology topology = graph.validateTopology();
            return topologyResult("graph.linear", "VALID", topology, null,
                    topology.valid() && topology.cyclicNodes().isEmpty());
        }
    }

    private EddResult reactCycle() {
        try (StateGraph graph = new StateGraph(5)) {
            graph.addNode("agent", state -> state)
                    .addNode("tool", state -> state)
                    .addConditionalEdges("agent", state -> "end",
                            Map.of("tool", "tool", "end", StateGraph.END))
                    .addEdge("tool", "agent")
                    .setEntryPoint("agent");
            GraphTopology topology = graph.validateTopology();
            return topologyResult("graph.react-cycle", "VALID", topology, null,
                    topology.valid()
                            && topology.cyclicNodes().containsAll(List.of("agent", "tool")));
        }
    }

    private StateGraph unreachableGraph() {
        StateGraph graph = new StateGraph(3);
        graph.addNode("start", state -> state)
                .addNode("orphan", state -> state)
                .addEdge("start", StateGraph.END)
                .setEntryPoint("start");
        return graph;
    }

    private StateGraph deadEndGraph() {
        StateGraph graph = new StateGraph(3);
        graph.addNode("start", state -> state).setEntryPoint("start");
        return graph;
    }

    private StateGraph noEndPathGraph() {
        StateGraph graph = new StateGraph(3);
        graph.addNode("start", state -> state)
                .addNode("loop", state -> state)
                .addEdge("start", "loop")
                .addEdge("loop", "loop")
                .setEntryPoint("start");
        return graph;
    }

    private EddResult invalid(String taskId, StateGraph graph) {
        try (graph) {
            graph.validateTopology();
            return new EddResult(taskId, "VALID", true, List.of(), List.of(),
                    List.of(), List.of(), null, false);
        } catch (GraphTopologyException exception) {
            GraphTopology topology = exception.topology();
            boolean expected = switch (taskId) {
                case "graph.unreachable" -> topology.unreachableNodes().contains("orphan");
                case "graph.dead-end" -> topology.deadEndNodes().contains("start");
                case "graph.no-end-path" -> topology.nodesWithoutEndPath().containsAll(
                        List.of("start", "loop"));
                default -> false;
            };
            return topologyResult(taskId, "INVALID", topology, null,
                    expected && !topology.valid());
        }
    }

    private EddResult subgraphBridge() {
        AtomicReference<UUID> childRunId = new AtomicReference<>();
        SubgraphStateBridge bridge = new SubgraphStateBridge() {
            @Override
            public AgentState project(AgentState parentState) {
                return AgentState.empty().withVariable(
                        "query", parentState.variables().get("task"));
            }

            @Override
            public AgentState merge(AgentState parentState, AgentState childState) {
                return parentState.withVariable("report", childState.variables().get("summary"));
            }
        };
        try (StateGraph graph = new StateGraph(3)) {
            graph.addNode("research", new SubgraphNode("research", () -> {
                StateGraph child = new StateGraph(2);
                child.addNode("search", new com.agent.core.engine.Node() {
                            @Override
                            public AgentState execute(
                                    com.agent.core.engine.NodeExecutionContext context,
                                    AgentState state) {
                                childRunId.set(context.runId());
                                return state.withVariable("summary", "done");
                            }

                            @Override
                            public AgentState execute(AgentState state) {
                                return state.withVariable("summary", "done");
                            }
                        })
                        .addEdge("search", StateGraph.END)
                        .setEntryPoint("search");
                return child;
            }, bridge))
                    .addEdge("research", StateGraph.END)
                    .setEntryPoint("research");
            UUID runId = UUID.randomUUID();
            GraphExecutionResult result = graph.execute(
                    new com.agent.core.engine.GraphExecutionRequest(
                            runId,
                            AgentState.empty().withVariable("task", "java"),
                            graph.entryPoint(),
                            false),
                    noOpListener());
            AgentState state = ((GraphExecutionResult.Completed) result).state();
            boolean passed = childRunId.get() != null
                    && childRunId.get().equals(runId)
                    && "done".equals(state.variables().get("report"))
                    && !state.variables().containsKey("query");
            return result("graph.subgraph-bridge", "COMPLETED", true,
                    List.of(), List.of(), List.of(), List.of(), null, passed);
        }
    }

    private EddResult subgraphInterrupt() {
        InterruptRequest request = new InterruptRequest(
                UUID.randomUUID(), "approval", "需要审批", Map.of("action", "write"));
        SubgraphStateBridge bridge = new SubgraphStateBridge() {
            @Override
            public AgentState project(AgentState parentState) {
                return parentState;
            }

            @Override
            public AgentState merge(AgentState parentState, AgentState childState) {
                return childState;
            }
        };
        try (StateGraph graph = new StateGraph(2)) {
            graph.addNode("approval", new SubgraphNode("approval", () -> {
                StateGraph child = new StateGraph(
                        2, (runId, nodeName, state) -> java.util.Optional.of(request));
                child.addNode("approval", state -> state)
                        .addEdge("approval", StateGraph.END)
                        .setEntryPoint("approval");
                return child;
            }, bridge))
                    .addEdge("approval", StateGraph.END)
                    .setEntryPoint("approval");
            graph.execute(AgentState.empty());
            return result("graph.subgraph-interrupt", "INTERRUPTED", true,
                    List.of(), List.of(), List.of(), List.of(), null, false);
        } catch (SubgraphInterruptedException exception) {
            return result("graph.subgraph-interrupt", "INTERRUPTED", true,
                    List.of(), List.of(), List.of(), List.of(), null,
                    exception.request().equals(request)
                            && exception.nodeName().equals("approval"));
        }
    }

    private EddResult loopBudget() {
        ExecutionBudget budget = new ExecutionBudget(
                Duration.ofSeconds(2), Duration.ofSeconds(1), 100, 2, 10);
        try (StateGraph graph = new StateGraph(budget, InterruptPolicy.never())) {
            graph.addNode("loop", state -> state.withTraceEntry("loop"))
                    .addEdge("loop", "loop")
                    .setEntryPoint("loop");
            graph.execute(AgentState.empty());
            return result("graph.loop-budget", "COMPLETED", true,
                    List.of(), List.of(), List.of(), List.of(), null, false);
        } catch (ExecutionBudgetExceededException exception) {
            return result("graph.loop-budget", "STOPPED", true,
                    List.of(), List.of(), List.of(), List.of(),
                    exception.reason().name(),
                    exception.reason() == ExecutionStopReason.MAX_STEPS);
        }
    }

    private EddResult topologyResult(
            String taskId,
            String status,
            GraphTopology topology,
            String stopReason,
            boolean passed) {
        return result(taskId, status, topology.valid(),
                List.copyOf(topology.unreachableNodes()),
                List.copyOf(topology.deadEndNodes()),
                List.copyOf(topology.nodesWithoutEndPath()),
                List.copyOf(topology.cyclicNodes()), stopReason, passed);
    }

    private EddResult result(
            String taskId,
            String status,
            boolean valid,
            List<String> unreachableNodes,
            List<String> deadEndNodes,
            List<String> nodesWithoutEndPath,
            List<String> cyclicNodes,
            String stopReason,
            boolean passed) {
        return new EddResult(taskId, status, valid, unreachableNodes, deadEndNodes,
                nodesWithoutEndPath, cyclicNodes, stopReason, passed);
    }

    private GraphExecutionListener noOpListener() {
        return new GraphExecutionListener() {
            @Override
            public void onNodeStarted(String nodeName, AgentState state) {
            }

            @Override
            public void onNodeCompleted(String nodeName, String nextNode, AgentState state) {
            }
        };
    }

    private record EddResult(
            String taskId,
            String status,
            boolean valid,
            List<String> unreachableNodes,
            List<String> deadEndNodes,
            List<String> nodesWithoutEndPath,
            List<String> cyclicNodes,
            String stopReason,
            boolean passed) {
    }
}
