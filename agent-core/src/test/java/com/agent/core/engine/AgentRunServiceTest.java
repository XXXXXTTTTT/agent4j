package com.agent.core.engine;

import com.agent.core.trace.TraceEvent;
import com.agent.core.trace.TraceEventType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRunServiceTest {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(5);

    @Test
    void bindsCreatedCheckpointBeforeDispatchingGraph() {
        InMemoryCheckpointer checkpointer = new InMemoryCheckpointer();
        AtomicBoolean bound = new AtomicBoolean();
        GraphRegistry registry = new GraphRegistry(Map.of("flow", () ->
                new StateGraph(1)
                        .addNode("done", state -> {
                            assertThat(bound).isTrue();
                            return state;
                        })
                        .addEdge("done", StateGraph.END)
                        .setEntryPoint("done")));

        try (AgentRunService service = new AgentRunService(
                checkpointer, registry, event -> { })) {
            RunCheckpoint started = service.start(
                    "flow", AgentState.empty(), checkpoint -> bound.set(true));

            assertThat(checkpointer.awaitStatus(
                    started.runId(), RunStatus.COMPLETED, AWAIT_TIMEOUT).status())
                    .isEqualTo(RunStatus.COMPLETED);
            assertThat(bound).isTrue();
        }
    }

    @Test
    void startsAndCompletesRunWithVersionedTraceAndClosedGraph() {
        InMemoryCheckpointer checkpointer = new InMemoryCheckpointer();
        List<TraceEvent> events = new CopyOnWriteArrayList<>();
        List<StateGraph> graphs = new CopyOnWriteArrayList<>();
        GraphRegistry registry = new GraphRegistry(Map.of("flow", () -> {
            StateGraph graph = new StateGraph(3);
            graph.addNode("first", state -> {
                        NodeExecutionContext.progress("正在执行 first");
                        return state
                                .withVariable("nodeVirtual", Boolean.toString(Thread.currentThread().isVirtual()))
                                .withTraceEntry("first");
                    })
                    .addNode("second", state -> state.withTraceEntry("second"))
                    .addEdge("first", "second")
                    .addEdge("second", StateGraph.END)
                    .setEntryPoint("first");
            graphs.add(graph);
            return graph;
        }));

        try (AgentRunService service = new AgentRunService(checkpointer, registry, events::add)) {
            RunCheckpoint started = service.start("flow", AgentState.empty());
            RunCheckpoint completed = checkpointer.awaitStatus(
                    started.runId(), RunStatus.COMPLETED, AWAIT_TIMEOUT);

            assertThat(started.version()).isZero();
            assertThat(started.status()).isEqualTo(RunStatus.RUNNING);
            assertThat(started.nextNode()).isEqualTo("first");
            assertThat(completed.version()).isEqualTo(2);
            assertThat(completed.state().trace()).containsExactly("first", "second");
            assertThat(completed.state().variables()).containsEntry("nodeVirtual", "true");
            assertThat(checkpointer.loadHistory(started.runId()))
                    .extracting(RunCheckpoint::status)
                    .containsExactly(RunStatus.RUNNING, RunStatus.RUNNING, RunStatus.COMPLETED);
            assertThat(checkpointer.loadHistory(started.runId()))
                    .extracting(RunCheckpoint::nextNode)
                    .containsExactly("first", "second", null);
            assertThat(events).extracting(TraceEvent::type).containsExactly(
                    TraceEventType.NODE_STARTED,
                    TraceEventType.NODE_PROGRESS,
                    TraceEventType.NODE_COMPLETED,
                    TraceEventType.NODE_STARTED,
                    TraceEventType.NODE_COMPLETED,
                    TraceEventType.COMPLETED);
            assertThat(events).extracting(TraceEvent::checkpointVersion)
                    .containsExactly(0L, 0L, 1L, 1L, 2L, 2L);
            assertThat(graphs).hasSize(1);
            assertThatThrownBy(graphs.getFirst()::entryPoint)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("关闭");
        }
    }

    @Test
    void interruptsThenApprovesAndBypassesOnlyTheInterruptedNode() {
        InMemoryCheckpointer checkpointer = new InMemoryCheckpointer();
        List<TraceEvent> events = new CopyOnWriteArrayList<>();
        List<StateGraph> graphs = new CopyOnWriteArrayList<>();
        InterruptRequest interrupt = interrupt("ops");
        GraphRegistry registry = new GraphRegistry(Map.of("approval", () -> {
            StateGraph graph = new StateGraph(
                    2,
                    (runId, nodeName, state) -> Optional.of(interrupt));
            graph.addNode("ops", state -> state.withTraceEntry("ops"))
                    .addEdge("ops", StateGraph.END)
                    .setEntryPoint("ops");
            graphs.add(graph);
            return graph;
        }));

        try (AgentRunService service = new AgentRunService(checkpointer, registry, events::add)) {
            RunCheckpoint started = service.start("approval", AgentState.empty());
            RunCheckpoint waiting = checkpointer.awaitStatus(
                    started.runId(), RunStatus.WAITING_APPROVAL, AWAIT_TIMEOUT);

            RunCheckpoint approved = service.decide(
                    started.runId(),
                    new ApprovalCommand(
                            ApprovalDecision.APPROVE,
                            waiting.version(),
                            "已核对命令和工作区"));
            RunCheckpoint completed = checkpointer.awaitStatus(
                    started.runId(), RunStatus.COMPLETED, AWAIT_TIMEOUT);

            assertThat(approved.status()).isEqualTo(RunStatus.RUNNING);
            assertThat(approved.nextNode()).isEqualTo("ops");
            assertThat(approved.approvalDecision()).isEqualTo(ApprovalDecision.APPROVE);
            assertThat(completed.state().trace()).containsExactly("ops");
            assertThat(checkpointer.loadHistory(started.runId()))
                    .extracting(RunCheckpoint::status)
                    .containsExactly(
                            RunStatus.RUNNING,
                            RunStatus.WAITING_APPROVAL,
                            RunStatus.RUNNING,
                            RunStatus.COMPLETED);
            assertThat(checkpointer.loadHistory(started.runId()).getLast().approvalDecision())
                    .isNull();
            assertThat(events).extracting(TraceEvent::type).containsExactly(
                    TraceEventType.INTERRUPTED,
                    TraceEventType.APPROVED,
                    TraceEventType.NODE_STARTED,
                    TraceEventType.NODE_COMPLETED,
                    TraceEventType.COMPLETED);
            assertThat(graphs).hasSize(2);
            assertThat(graphs).allSatisfy(graph ->
                    assertThatThrownBy(graph::entryPoint)
                            .isInstanceOf(IllegalStateException.class));
        }
    }

    @Test
    void approvesExactWhitelistedVariableUpdateWithoutMutatingWaitingState() {
        InMemoryCheckpointer checkpointer = new InMemoryCheckpointer();
        InterruptRequest interrupt = new InterruptRequest(
                UUID.fromString("bc6c5920-c6ed-42c8-9d72-adab29365243"),
                "ops",
                "需要人工审批",
                Map.of("ops.command", "mvn test", "missing", "not present"));
        GraphRegistry registry = new GraphRegistry(Map.of("approval", () ->
                new StateGraph(2, (runId, nodeName, state) -> Optional.of(interrupt))
                        .addNode("ops", state -> state.withTraceEntry("ops"))
                        .addEdge("ops", StateGraph.END)
                        .setEntryPoint("ops")));
        AgentState initialState = AgentState.empty()
                .withVariable("ops.command", "mvn test")
                .withVariable("protected", "keep");

        try (AgentRunService service = new AgentRunService(
                checkpointer, registry, event -> { })) {
            RunCheckpoint started = service.start("approval", initialState);
            RunCheckpoint waiting = checkpointer.awaitStatus(
                    started.runId(), RunStatus.WAITING_APPROVAL, AWAIT_TIMEOUT);

            assertThatThrownBy(() -> service.decide(
                    started.runId(),
                    new ApprovalCommand(
                            ApprovalDecision.APPROVE,
                            waiting.version(),
                            "非法受保护变量",
                            Map.of("protected", "changed"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("protected");
            assertThatThrownBy(() -> service.decide(
                    started.runId(),
                    new ApprovalCommand(
                            ApprovalDecision.APPROVE,
                            waiting.version(),
                            "非法缺失变量",
                            Map.of("missing", "changed"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("missing");
            assertThat(checkpointer.loadHistory(started.runId())).hasSize(2);

            RunCheckpoint approved = service.decide(
                    started.runId(),
                    new ApprovalCommand(
                            ApprovalDecision.APPROVE,
                            waiting.version(),
                            "批准修改",
                            Map.of("ops.command", "mvn verify")));

            assertThat(waiting.state().variables())
                    .containsEntry("ops.command", "mvn test")
                    .containsEntry("protected", "keep");
            assertThat(approved.state().variables())
                    .containsEntry("ops.command", "mvn verify")
                    .containsEntry("protected", "keep");
        }
    }

    @Test
    void validatesAndCopiesApprovalVariableUpdates() {
        Map<String, String> updates = new LinkedHashMap<>();
        updates.put("ops.command", "mvn verify");
        ApprovalCommand command = new ApprovalCommand(
                ApprovalDecision.APPROVE, 1, "批准", updates);
        updates.put("ops.command", "changed later");

        assertThat(command.variableUpdates())
                .containsExactlyEntriesOf(Map.of("ops.command", "mvn verify"));
        assertThat(new ApprovalCommand(
                ApprovalDecision.APPROVE, 1, "批准").variableUpdates()).isEmpty();
        assertThatThrownBy(() -> new ApprovalCommand(
                ApprovalDecision.REJECT,
                1,
                "拒绝",
                Map.of("ops.command", "mvn verify")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApprovalCommand(
                ApprovalDecision.APPROVE, 1, "批准", Map.of(" ", "value")))
                .isInstanceOf(IllegalArgumentException.class);
        Map<String, String> nullValue = new LinkedHashMap<>();
        nullValue.put("ops.command", null);
        assertThatThrownBy(() -> new ApprovalCommand(
                ApprovalDecision.APPROVE, 1, "批准", nullValue))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApprovalCommand(
                ApprovalDecision.APPROVE, 1, "批准", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsWaitingRunWithoutSchedulingAnotherGraph() {
        InMemoryCheckpointer checkpointer = new InMemoryCheckpointer();
        List<TraceEvent> events = new CopyOnWriteArrayList<>();
        List<StateGraph> graphs = new CopyOnWriteArrayList<>();
        GraphRegistry registry = interruptingRegistry("approval", "ops", graphs);

        try (AgentRunService service = new AgentRunService(checkpointer, registry, events::add)) {
            RunCheckpoint started = service.start("approval", AgentState.empty());
            RunCheckpoint waiting = checkpointer.awaitStatus(
                    started.runId(), RunStatus.WAITING_APPROVAL, AWAIT_TIMEOUT);

            RunCheckpoint rejected = service.decide(
                    started.runId(),
                    new ApprovalCommand(
                            ApprovalDecision.REJECT,
                            waiting.version(),
                            "该操作不允许执行"));

            assertThat(rejected.status()).isEqualTo(RunStatus.REJECTED);
            assertThat(rejected.interruptRequest()).isEqualTo(waiting.interruptRequest());
            assertThat(rejected.approvalReason()).isEqualTo("该操作不允许执行");
            assertThat(graphs).hasSize(1);
            assertThat(events).extracting(TraceEvent::type)
                    .containsExactly(TraceEventType.INTERRUPTED, TraceEventType.REJECTED);
        }
    }

    @Test
    void rejectsStaleOrTerminalApprovalAndAllowsOnlyOneConcurrentDecision()
            throws Exception {
        InMemoryCheckpointer checkpointer = new InMemoryCheckpointer();
        GraphRegistry registry = interruptingRegistry("approval", "ops", new CopyOnWriteArrayList<>());

        try (AgentRunService service = new AgentRunService(
                checkpointer, registry, event -> { })) {
            RunCheckpoint started = service.start("approval", AgentState.empty());
            RunCheckpoint waiting = checkpointer.awaitStatus(
                    started.runId(), RunStatus.WAITING_APPROVAL, AWAIT_TIMEOUT);
            ApprovalCommand reject = new ApprovalCommand(
                    ApprovalDecision.REJECT, waiting.version(), "拒绝执行");

            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                List<Future<Object>> decisions = List.of(
                        executor.submit(() -> decideResult(service, started.runId(), reject)),
                        executor.submit(() -> decideResult(service, started.runId(), reject)));
                List<Object> results = new ArrayList<>();
                for (Future<Object> decision : decisions) {
                    results.add(decision.get(5, TimeUnit.SECONDS));
                }
                assertThat(results).filteredOn(RunCheckpoint.class::isInstance).hasSize(1);
                assertThat(results).filteredOn(CheckpointConflictException.class::isInstance)
                        .hasSize(1);
            }

            assertThatThrownBy(() -> service.decide(
                    started.runId(),
                    new ApprovalCommand(
                            ApprovalDecision.REJECT,
                            waiting.version() - 1,
                            "旧版本")))
                    .isInstanceOf(CheckpointConflictException.class);
        }
    }

    @Test
    void recoversApprovedRunningCheckpointWithOneBypass() {
        InMemoryCheckpointer checkpointer = new InMemoryCheckpointer();
        UUID runId = UUID.fromString("80478893-33ff-4230-8cf2-5670fc85e011");
        InterruptRequest interrupt = interrupt("ops");
        RunCheckpoint created = checkpointer.create(runId, "approval", AgentState.empty(), "ops");
        RunCheckpoint waiting = checkpointer.append(new CheckpointAppend(
                runId,
                created.version(),
                RunStatus.WAITING_APPROVAL,
                created.state(),
                "ops",
                interrupt,
                null,
                null,
                null));
        checkpointer.append(new CheckpointAppend(
                runId,
                waiting.version(),
                RunStatus.RUNNING,
                waiting.state(),
                "ops",
                null,
                ApprovalDecision.APPROVE,
                "已批准",
                null));
        GraphRegistry registry = interruptingRegistry("approval", "ops", new CopyOnWriteArrayList<>());

        try (AgentRunService service = new AgentRunService(
                checkpointer, registry, event -> { })) {
            service.recoverRunningRuns();

            RunCheckpoint completed = checkpointer.awaitStatus(
                    runId, RunStatus.COMPLETED, AWAIT_TIMEOUT);
            assertThat(completed.state().trace()).containsExactly("ops");
            assertThat(completed.approvalDecision()).isNull();
        }
    }

    @Test
    void storesFullFailureStackAndKeepsCheckpointWhenPublisherFails() {
        InMemoryCheckpointer failedCheckpointer = new InMemoryCheckpointer();
        GraphRegistry failedRegistry = new GraphRegistry(Map.of("failure", () ->
                new StateGraph(1)
                        .addNode("tool", state -> {
                            throw new IOException("tool unavailable");
                        })
                        .addEdge("tool", StateGraph.END)
                        .setEntryPoint("tool")));

        try (AgentRunService service = new AgentRunService(
                failedCheckpointer, failedRegistry, event -> { })) {
            RunCheckpoint started = service.start("failure", AgentState.empty());
            RunCheckpoint failed = failedCheckpointer.awaitStatus(
                    started.runId(), RunStatus.FAILED, AWAIT_TIMEOUT);

            assertThat(failed.error())
                    .contains(GraphExecutionException.class.getName())
                    .contains(IOException.class.getName())
                    .contains("tool unavailable")
                    .contains("\tat ");
        }

        InMemoryCheckpointer successfulCheckpointer = new InMemoryCheckpointer();
        GraphRegistry successfulRegistry = new GraphRegistry(Map.of("success", () ->
                new StateGraph(1)
                        .addNode("done", state -> state)
                        .addEdge("done", StateGraph.END)
                        .setEntryPoint("done")));
        try (AgentRunService service = new AgentRunService(
                successfulCheckpointer,
                successfulRegistry,
                event -> { throw new IllegalStateException("trace unavailable"); })) {
            RunCheckpoint started = service.start("success", AgentState.empty());

            assertThat(successfulCheckpointer.awaitStatus(
                    started.runId(), RunStatus.COMPLETED, AWAIT_TIMEOUT).status())
                    .isEqualTo(RunStatus.COMPLETED);
        }
    }

    @Test
    void getsLatestCheckpointAndHistoryAndRejectsUnknownRun() {
        InMemoryCheckpointer checkpointer = new InMemoryCheckpointer();
        GraphRegistry registry = new GraphRegistry(Map.of("success", () ->
                new StateGraph(1)
                        .addNode("done", state -> state)
                        .addEdge("done", StateGraph.END)
                        .setEntryPoint("done")));

        try (AgentRunService service = new AgentRunService(
                checkpointer, registry, event -> { })) {
            RunCheckpoint started = service.start("success", AgentState.empty());

            assertThat(service.get(started.runId()).runId()).isEqualTo(started.runId());
            assertThat(service.history(started.runId()))
                    .isNotEmpty()
                    .first()
                    .isEqualTo(started);
            UUID missing = UUID.fromString("3dc442ae-3158-428f-a64d-44cb88830dd0");
            assertThatThrownBy(() -> service.get(missing))
                    .isInstanceOfSatisfying(RunNotFoundException.class, exception ->
                            assertThat(exception.runId()).isEqualTo(missing));
            assertThatThrownBy(() -> service.history(missing))
                    .isInstanceOfSatisfying(RunNotFoundException.class, exception ->
                            assertThat(exception.runId()).isEqualTo(missing));
        }
    }

    @Test
    void cancelsRunningRunAndInterruptsNode() throws Exception {
        InMemoryCheckpointer checkpointer = new InMemoryCheckpointer();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        GraphRegistry registry = new GraphRegistry(Map.of("slow", () ->
                new StateGraph(1)
                        .addNode("wait", state -> {
                            entered.countDown();
                            try {
                                Thread.sleep(Duration.ofSeconds(30));
                                return state;
                            } catch (InterruptedException exception) {
                                interrupted.set(true);
                                Thread.currentThread().interrupt();
                                throw exception;
                            }
                        })
                        .addEdge("wait", StateGraph.END)
                        .setEntryPoint("wait")));
        List<TraceEvent> events = new CopyOnWriteArrayList<>();

        try (AgentRunService service = new AgentRunService(checkpointer, registry, events::add)) {
            RunCheckpoint started = service.start("slow", AgentState.empty());
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            RunCheckpoint cancelled = service.cancel(started.runId(), "Benchmark 执行超时");

            assertThat(cancelled.status()).isEqualTo(RunStatus.FAILED);
            assertThat(cancelled.error())
                    .contains("CancellationException")
                    .contains("Benchmark 执行超时");
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (!interrupted.get() && System.nanoTime() - deadline < 0) {
                Thread.onSpinWait();
            }
            assertThat(interrupted).isTrue();
            assertThat(events).anyMatch(event -> event instanceof TraceEvent.Failed);
        }
    }

    private Object decideResult(
            AgentRunService service,
            UUID runId,
            ApprovalCommand command) {
        try {
            return service.decide(runId, command);
        } catch (CheckpointConflictException exception) {
            return exception;
        }
    }

    private GraphRegistry interruptingRegistry(
            String graphId,
            String nodeName,
            List<StateGraph> graphs) {
        return new GraphRegistry(Map.of(graphId, () -> {
            InterruptRequest interrupt = interrupt(nodeName);
            StateGraph graph = new StateGraph(
                    2,
                    (runId, currentNode, state) -> Optional.of(interrupt));
            graph.addNode(nodeName, state -> state.withTraceEntry(nodeName))
                    .addEdge(nodeName, StateGraph.END)
                    .setEntryPoint(nodeName);
            graphs.add(graph);
            return graph;
        }));
    }

    private InterruptRequest interrupt(String nodeName) {
        return new InterruptRequest(
                UUID.fromString("1789dc76-2fa3-4f45-a0c1-73404f14ab6f"),
                nodeName,
                "需要人工审批",
                Map.of("command", "mvn verify"));
    }

    private static final class InMemoryCheckpointer implements Checkpointer {

        private final ReentrantLock lock = new ReentrantLock();
        private final Condition changed = lock.newCondition();
        private final Map<UUID, List<RunCheckpoint>> histories = new LinkedHashMap<>();

        @Override
        public RunCheckpoint create(
                UUID runId,
                String graphId,
                AgentState initialState,
                String entryNode) {
            lock.lock();
            try {
                if (histories.containsKey(runId)) {
                    throw new IllegalStateException("Run 已存在: " + runId);
                }
                RunCheckpoint checkpoint = new RunCheckpoint(
                        runId,
                        0,
                        graphId,
                        RunStatus.RUNNING,
                        initialState,
                        entryNode,
                        null,
                        null,
                        null,
                        null,
                        Instant.now());
                histories.put(runId, new ArrayList<>(List.of(checkpoint)));
                changed.signalAll();
                return checkpoint;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public RunCheckpoint append(CheckpointAppend append) {
            lock.lock();
            try {
                List<RunCheckpoint> history = histories.get(append.runId());
                if (history == null) {
                    throw new RunNotFoundException(append.runId());
                }
                RunCheckpoint latest = history.getLast();
                if (latest.version() != append.expectedVersion()) {
                    throw new CheckpointConflictException(
                            append.runId(), append.expectedVersion());
                }
                RunCheckpoint checkpoint = new RunCheckpoint(
                        append.runId(),
                        append.expectedVersion() + 1,
                        latest.graphId(),
                        append.status(),
                        append.state(),
                        append.nextNode(),
                        append.interruptRequest(),
                        append.approvalDecision(),
                        append.approvalReason(),
                        append.error(),
                        Instant.now());
                history.add(checkpoint);
                changed.signalAll();
                return checkpoint;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public Optional<RunCheckpoint> loadLatest(UUID runId) {
            lock.lock();
            try {
                List<RunCheckpoint> history = histories.get(runId);
                return history == null ? Optional.empty() : Optional.of(history.getLast());
            } finally {
                lock.unlock();
            }
        }

        @Override
        public List<RunCheckpoint> loadHistory(UUID runId) {
            lock.lock();
            try {
                List<RunCheckpoint> history = histories.get(runId);
                return history == null ? List.of() : List.copyOf(history);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public List<RunCheckpoint> loadLatestByStatus(RunStatus status) {
            lock.lock();
            try {
                return histories.values().stream()
                        .map(List::getLast)
                        .filter(checkpoint -> checkpoint.status() == status)
                        .sorted(Comparator.comparing(RunCheckpoint::createdAt))
                        .toList();
            } finally {
                lock.unlock();
            }
        }

        RunCheckpoint awaitStatus(UUID runId, RunStatus status, Duration timeout) {
            return await(
                    () -> loadLatest(runId).filter(checkpoint -> checkpoint.status() == status),
                    timeout);
        }

        private RunCheckpoint await(
                Supplier<Optional<RunCheckpoint>> condition,
                Duration timeout) {
            long remaining = timeout.toNanos();
            lock.lock();
            try {
                while (remaining > 0) {
                    Optional<RunCheckpoint> result = condition.get();
                    if (result.isPresent()) {
                        return result.orElseThrow();
                    }
                    try {
                        remaining = changed.awaitNanos(remaining);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("等待 Checkpoint 时被中断", exception);
                    }
                }
                throw new AssertionError("等待状态超时: " + statusSummary());
            } finally {
                lock.unlock();
            }
        }

        private String statusSummary() {
            return histories.values().stream()
                    .map(List::getLast)
                    .map(checkpoint -> checkpoint.runId() + "=" + checkpoint.status())
                    .sorted()
                    .toList()
                    .toString();
        }
    }
}
