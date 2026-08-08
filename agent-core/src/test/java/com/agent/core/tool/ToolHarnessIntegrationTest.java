package com.agent.core.tool;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.ExecutionBudget;
import com.agent.core.engine.InterruptPolicy;
import com.agent.core.engine.NodeExecutionContext;
import com.agent.core.engine.StateGraph;
import com.agent.core.harness.HarnessEvent;
import com.agent.core.harness.HarnessEventType;
import com.agent.core.harness.HarnessHook;
import com.agent.core.harness.HarnessHookChain;
import com.agent.core.harness.HarnessHookException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolHarnessIntegrationTest {

    private static final ExecutionBudget BUDGET = new ExecutionBudget(
            Duration.ofSeconds(2), Duration.ofSeconds(1), 100, 5, 3);

    @Test
    void publishesToolLifecycleThroughRealStateGraphAndHidesArguments() {
        List<HarnessEvent> events = new CopyOnWriteArrayList<>();
        List<ToolAuditEvent> audits = new CopyOnWriteArrayList<>();
        HarnessToolExecutor toolExecutor = new HarnessToolExecutor(registry(audits));
        try (StateGraph graph = new StateGraph(BUDGET, InterruptPolicy.never(),
                new HarnessHookChain(List.of(events::add)))) {
            graph.addNode("ops", state -> {
                        NodeExecutionContext context = NodeExecutionContext.current().orElseThrow();
                        ToolInvocationContext toolContext = new ToolInvocationContext(
                                context.runId(), context.nodeName(), "user-a", Path.of("."), Set.of(), false);
                        ToolResult result = toolExecutor.execute(
                                new ToolCall("call-1", "echo.tool",
                                        JsonNodeFactory.instance.objectNode().put("secret", "hidden")), toolContext);
                        return state.withVariable("status", result.status().name());
                    })
                    .addEdge("ops", StateGraph.END)
                    .setEntryPoint("ops");

            AgentState state = graph.execute(AgentState.empty());
            assertThat(state.variables()).containsEntry("status", ToolResultStatus.SUCCEEDED.name());
        }
        assertThat(events).extracting(HarnessEvent::eventType).containsExactly(
                HarnessEventType.BEFORE_NODE, HarnessEventType.BEFORE_TOOL,
                HarnessEventType.AFTER_TOOL, HarnessEventType.AFTER_NODE);
        assertThat(events.get(1).metadata()).containsOnlyKeys("toolName", "callId", "riskLevel")
                .containsEntry("toolName", "echo.tool");
        assertThat(events.get(1).metadata()).doesNotContainKey("arguments");
        assertThat(audits).hasSize(1).allSatisfy(event ->
                assertThat(event.status()).isEqualTo(ToolResultStatus.SUCCEEDED));
    }

    @Test
    void publishesFailureForGovernanceResultAndReturnsOriginalResult() throws Exception {
        List<HarnessEvent> events = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ToolRegistry registry = new DefaultToolRegistry(
                new JacksonToolSchemaValidator(), new DefaultToolAuthorizer(), event -> {
                }, new ObjectMapper(), System::nanoTime);
        registry.register(new ToolDefinition("danger.tool", "危险工具",
                JsonNodeFactory.instance.objectNode().put("type", "object"), Set.of(),
                ToolRiskLevel.HIGH, Duration.ofSeconds(1), (call, context) -> {
                    calls.incrementAndGet();
                    return JsonNodeFactory.instance.objectNode();
                }));
        try (registry; StateGraph graph = new StateGraph(BUDGET, InterruptPolicy.never(),
                new HarnessHookChain(List.of(events::add)))) {
            HarnessToolExecutor toolExecutor = new HarnessToolExecutor(registry);
            graph.addNode("ops", state -> {
                        NodeExecutionContext context = NodeExecutionContext.current().orElseThrow();
                        ToolInvocationContext toolContext = new ToolInvocationContext(
                                context.runId(), context.nodeName(), "user-a", Path.of("."), Set.of(), false);
                        ToolResult result = toolExecutor.execute(
                                new ToolCall("call-approval", "danger.tool", JsonNodeFactory.instance.objectNode()),
                                toolContext);
                        return state.withVariable("status", result.status().name());
                    })
                    .addEdge("ops", StateGraph.END)
                    .setEntryPoint("ops");
            AgentState state = graph.execute(AgentState.empty());
            assertThat(state.variables()).containsEntry("status", ToolResultStatus.APPROVAL_REQUIRED.name());
        }
        assertThat(calls).hasValue(0);
        assertThat(events).extracting(HarnessEvent::eventType).containsExactly(
                HarnessEventType.BEFORE_NODE, HarnessEventType.BEFORE_TOOL,
                HarnessEventType.FAILURE, HarnessEventType.AFTER_NODE);
    }

    @Test
    void nonCriticalHookFailureIsAuditedAndDoesNotChangeToolResult() {
        List<HarnessHookException> hookFailures = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        DefaultToolRegistry registry = new DefaultToolRegistry(
                new JacksonToolSchemaValidator(), new DefaultToolAuthorizer(), event -> {
                }, new ObjectMapper(), System::nanoTime);
        registry.register(new ToolDefinition("observed.tool", "观测工具",
                JsonNodeFactory.instance.objectNode().put("type", "object"), Set.of(),
                ToolRiskLevel.LOW, Duration.ofSeconds(1), (call, context) -> {
                    calls.incrementAndGet();
                    return JsonNodeFactory.instance.objectNode().put("ok", true);
                }));
        HarnessHook failing = event -> {
            if (event.eventType() == HarnessEventType.BEFORE_TOOL) {
                throw new IllegalStateException("observer failed");
            }
        };
        try (registry; StateGraph graph = new StateGraph(BUDGET, InterruptPolicy.never(),
                new HarnessHookChain(List.of(failing), hookFailures::add))) {
            HarnessToolExecutor toolExecutor = new HarnessToolExecutor(registry);
            graph.addNode("ops", state -> {
                        NodeExecutionContext context = NodeExecutionContext.current().orElseThrow();
                        ToolInvocationContext toolContext = new ToolInvocationContext(
                                context.runId(), context.nodeName(), "user-a", Path.of("."), Set.of(), false);
                        ToolResult result = toolExecutor.execute(
                                new ToolCall("call-observed", "observed.tool",
                                        JsonNodeFactory.instance.objectNode()), toolContext);
                        return state.withVariable("status", result.status().name());
                    })
                    .addEdge("ops", StateGraph.END)
                    .setEntryPoint("ops");
            AgentState state = graph.execute(AgentState.empty());
            assertThat(state.variables()).containsEntry("status", ToolResultStatus.SUCCEEDED.name());
        }
        assertThat(calls).hasValue(1);
        assertThat(hookFailures).hasSize(1).allSatisfy(failure ->
                assertThat(failure.getCause()).hasMessage("observer failed"));
    }

    @Test
    void criticalBeforeHookStopsHandlerAndPreservesHookException() {
        AtomicInteger calls = new AtomicInteger();
        HarnessHook critical = new HarnessHook() {
            @Override
            public void onEvent(HarnessEvent event) {
                if (event.eventType() == HarnessEventType.BEFORE_TOOL) {
                    throw new IllegalStateException("critical before failed");
                }
            }

            @Override
            public boolean critical() {
                return true;
            }
        };
        try (DefaultToolRegistry registry = (DefaultToolRegistry) registry(new ArrayList<>());
             StateGraph graph = new StateGraph(BUDGET, InterruptPolicy.never(),
                     new HarnessHookChain(List.of(critical)))) {
            HarnessToolExecutor toolExecutor = new HarnessToolExecutor(registry);
            graph.addNode("ops", state -> {
                        NodeExecutionContext context = NodeExecutionContext.current().orElseThrow();
                        ToolInvocationContext toolContext = new ToolInvocationContext(
                                context.runId(), context.nodeName(), "user-a", Path.of("."), Set.of(), false);
                        toolExecutor.execute(new ToolCall("call-critical", "echo.tool",
                                JsonNodeFactory.instance.objectNode()), toolContext);
                        return state;
                    })
                    .addEdge("ops", StateGraph.END)
                    .setEntryPoint("ops");
            assertThatThrownBy(() -> graph.execute(AgentState.empty()))
                    .hasRootCauseMessage("critical before failed");
            assertThat(calls).hasValue(0);
        }
    }

    private ToolRegistry registry(List<ToolAuditEvent> audits) {
        DefaultToolRegistry registry = new DefaultToolRegistry(
                new JacksonToolSchemaValidator(), new DefaultToolAuthorizer(), audits::add,
                new ObjectMapper(), System::nanoTime);
        registry.register(new ToolDefinition("echo.tool", "回显工具",
                JsonNodeFactory.instance.objectNode().put("type", "object"), Set.of(),
                ToolRiskLevel.LOW, Duration.ofSeconds(1), (call, context) ->
                JsonNodeFactory.instance.objectNode().put("ok", true)));
        return registry;
    }
}
