package com.agent.core.engine;

import com.agent.core.harness.HarnessEvent;
import com.agent.core.harness.HarnessEventType;
import com.agent.core.harness.HarnessHookChain;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 使用 Java 21 虚拟线程逐节点驱动的状态图。
 */
public final class StateGraph implements AutoCloseable {

    /** 图的唯一终点标识。 */
    public static final String END = "__END__";

    private static final Duration LEGACY_DURATION = Duration.ofDays(3650);

    private final ExecutionBudget budget;
    private final InterruptPolicy interruptPolicy;
    private final HarnessHookChain harness;
    private final ExecutorService executor;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, String> edges = new LinkedHashMap<>();
    private final Map<String, ConditionalTransition> conditionalEdges = new LinkedHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    private String entryPoint;

    /**
     * 创建状态图。
     *
     * @param maxSteps 单次执行允许的最大节点步数
     */
    public StateGraph(int maxSteps) {
        this(legacyBudget(maxSteps), InterruptPolicy.never());
    }

    /**
     * 创建支持节点前中断的状态图。
     *
     * @param maxSteps       单次执行允许的最大节点步数
     * @param interruptPolicy 节点执行前中断策略
     */
    public StateGraph(int maxSteps, InterruptPolicy interruptPolicy) {
        this(legacyBudget(maxSteps), interruptPolicy);
    }

    /** 创建受完整执行预算约束的状态图。 */
    public StateGraph(ExecutionBudget budget) {
        this(budget, InterruptPolicy.never());
    }

    /** 创建受完整执行预算和中断策略约束的状态图。 */
    public StateGraph(ExecutionBudget budget, InterruptPolicy interruptPolicy) {
        this(budget, interruptPolicy, HarnessHookChain.noop());
    }

    /** 创建受预算、中断策略和 Harness Hook 约束的状态图。 */
    public StateGraph(
            ExecutionBudget budget,
            InterruptPolicy interruptPolicy,
            HarnessHookChain harness) {
        this.budget = Objects.requireNonNull(budget, "budget 不能为空");
        this.interruptPolicy = Objects.requireNonNull(
                interruptPolicy, "interruptPolicy 不能为空");
        this.harness = Objects.requireNonNull(harness, "harness 不能为空");
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 注册节点。
     *
     * @param name 节点名称
     * @param node 节点实现
     * @return 当前图
     */
    public StateGraph addNode(String name, Node node) {
        ensureOpen();
        validateNodeName(name);
        Objects.requireNonNull(node, "node 不能为空");
        if (nodes.putIfAbsent(name, node) != null) {
            throw new IllegalArgumentException("节点已注册: " + name);
        }
        return this;
    }

    /**
     * 设置图入口。
     *
     * @param name 已注册节点名称
     * @return 当前图
     */
    public StateGraph setEntryPoint(String name) {
        ensureOpen();
        requireRegisteredNode(name);
        this.entryPoint = name;
        return this;
    }

    /**
     * 添加普通有向边。
     *
     * @param source 来源节点
     * @param target 目标节点或 {@link #END}
     * @return 当前图
     */
    public StateGraph addEdge(String source, String target) {
        ensureOpen();
        requireRegisteredNode(source);
        requireTarget(target);
        if (conditionalEdges.containsKey(source)) {
            throw new IllegalStateException("节点已存在条件边: " + source);
        }
        if (edges.putIfAbsent(source, target) != null) {
            throw new IllegalStateException("节点已存在普通边: " + source);
        }
        return this;
    }

    /**
     * 添加条件路由边。
     *
     * @param source    来源节点
     * @param condition 路由条件
     * @param routes    路由键到目标节点的精确映射
     * @return 当前图
     */
    public StateGraph addConditionalEdges(
            String source,
            Condition condition,
            Map<String, String> routes) {
        ensureOpen();
        requireRegisteredNode(source);
        Objects.requireNonNull(condition, "condition 不能为空");
        Objects.requireNonNull(routes, "routes 不能为空");
        if (routes.isEmpty()) {
            throw new IllegalArgumentException("routes 不能为空映射");
        }
        if (edges.containsKey(source)) {
            throw new IllegalStateException("节点已存在普通边: " + source);
        }
        if (conditionalEdges.containsKey(source)) {
            throw new IllegalStateException("节点已存在条件边: " + source);
        }

        Map<String, String> checkedRoutes = new LinkedHashMap<>();
        routes.forEach((route, target) -> {
            if (route == null || route.isBlank()) {
                throw new IllegalArgumentException("路由键不能为空");
            }
            requireTarget(target);
            checkedRoutes.put(route, target);
        });
        conditionalEdges.put(source, new ConditionalTransition(condition, Map.copyOf(checkedRoutes)));
        return this;
    }

    /**
     * 从入口执行图并返回最终状态。
     *
     * @param initialState 初始不可变状态
     * @return 到达终点时的状态
     */
    public AgentState execute(AgentState initialState) {
        ensureOpen();
        Objects.requireNonNull(initialState, "initialState 不能为空");
        if (entryPoint == null) {
            throw new IllegalStateException("尚未设置入口节点");
        }

        GraphExecutionResult result = execute(
                new GraphExecutionRequest(
                        UUID.randomUUID(), initialState, entryPoint, false),
                NoOpGraphExecutionListener.INSTANCE);
        if (result instanceof GraphExecutionResult.Completed completed) {
            return completed.state();
        }
        GraphExecutionResult.Interrupted interrupted =
                (GraphExecutionResult.Interrupted) result;
        throw new IllegalStateException("图执行被中断: " + interrupted.nodeName());
    }

    /**
     * 返回图入口节点的精确名称。
     *
     * @return 入口节点名称
     */
    public String entryPoint() {
        ensureOpen();
        if (entryPoint == null) {
            throw new IllegalStateException("尚未设置入口节点");
        }
        return entryPoint;
    }

    /**
     * 从请求指定节点执行图。
     *
     * @param request  执行请求
     * @param listener 节点边界监听器
     * @return 完成或挂起结果
     */
    public GraphExecutionResult execute(
            GraphExecutionRequest request,
            GraphExecutionListener listener) {
        ensureOpen();
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(listener, "listener 不能为空");
        requireRegisteredNode(request.startNode());

        String currentNode = request.startNode();
        AgentState currentState = request.state();
        boolean bypassInterrupt = request.bypassInterruptAtStart();
        int steps = 0;
        int noProgress = 0;
        ExecutionTracker tracker = new ExecutionTracker(budget);

        while (!END.equals(currentNode)) {
            try {
                tracker.checkAll(steps, noProgress);
            } catch (ExecutionBudgetExceededException exception) {
                publishBudgetExhausted(
                        request.runId(), currentNode, currentState, exception);
                throw exception;
            }
            if (bypassInterrupt) {
                bypassInterrupt = false;
            } else {
                Optional<InterruptRequest> evaluated = Objects.requireNonNull(
                        interruptPolicy.evaluate(request.runId(), currentNode, currentState),
                        "interruptPolicy 返回值不能为空");
                if (evaluated.isPresent()) {
                    InterruptRequest interruptRequest = evaluated.orElseThrow();
                    if (!currentNode.equals(interruptRequest.nodeName())) {
                        throw new IllegalStateException(
                                "中断请求节点名称与当前节点不一致: current="
                                        + currentNode
                                        + ", request="
                                        + interruptRequest.nodeName());
                    }
                    return new GraphExecutionResult.Interrupted(
                            currentState, currentNode, interruptRequest);
                }
            }
            publishHarness(
                    request.runId(), currentNode, HarnessEventType.BEFORE_NODE,
                    currentState, Map.of());
            listener.onNodeStarted(currentNode, currentState);
            tracker.markProgress();
            AgentState previousState = currentState;
            try {
                currentState = executeNode(
                        request.runId(), currentNode, currentState, listener, tracker);
                tracker.checkActive();
            } catch (ExecutionBudgetExceededException exception) {
                publishBudgetExhausted(
                        request.runId(), currentNode, currentState, exception);
                throw exception;
            } catch (RuntimeException exception) {
                publishHarness(
                        request.runId(), currentNode, HarnessEventType.FAILURE,
                        currentState,
                        Map.of("errorType", exception.getClass().getName()));
                throw exception;
            }
            steps++;
            if (sameProgressState(previousState, currentState)) {
                noProgress++;
            } else {
                noProgress = 0;
                tracker.markProgress();
            }
            String nextNode = resolveNextNode(currentNode, currentState);
            publishHarness(
                    request.runId(), currentNode, HarnessEventType.AFTER_NODE,
                    currentState, Map.of("nextNode", nextNode));
            listener.onNodeCompleted(currentNode, nextNode, currentState);
            currentNode = nextNode;
        }
        return new GraphExecutionResult.Completed(currentState);
    }

    private AgentState executeNode(
            UUID runId,
            String nodeName,
            AgentState state,
            GraphExecutionListener listener,
            ExecutionTracker tracker) {
        Node node = nodes.get(nodeName);
        if (node == null) {
            throw new IllegalStateException("节点未注册: " + nodeName);
        }

        NodeExecutionContext context = new NodeExecutionContext(runId, nodeName);
        long tokenBase = tracker.consumedTokens();
        Future<AgentState> future = executor.submit(() ->
                NodeExecutionContext.callWithin(
                        context,
                        summary -> listener.onNodeProgress(nodeName, summary),
                        nodeTokens -> tracker.recordNodeTokens(tokenBase, nodeTokens),
                        tracker::markProgress,
                        state,
                        harness,
                        () -> node.execute(context, state)));
        try {
            while (true) {
                tracker.checkActive();
                try {
                    AgentState result = future.get(
                            tracker.nextWaitNanos(), TimeUnit.NANOSECONDS);
                    if (result == null) {
                        throw new NullPointerException("节点返回状态不能为空");
                    }
                    return result;
                } catch (TimeoutException exception) {
                    tracker.checkActive();
                }
            }
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new GraphExecutionException(nodeName, exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ExecutionBudgetExceededException budgetFailure) {
                throw budgetFailure;
            }
            throw new GraphExecutionException(nodeName, cause);
        } catch (ExecutionBudgetExceededException exception) {
            future.cancel(true);
            throw exception;
        } catch (RuntimeException exception) {
            throw new GraphExecutionException(nodeName, exception);
        }
    }

    private boolean sameProgressState(AgentState previous, AgentState current) {
        return previous.messages().equals(current.messages())
                && previous.variables().equals(current.variables());
    }

    private void publishBudgetExhausted(
            UUID runId,
            String nodeName,
            AgentState state,
            ExecutionBudgetExceededException exception) {
        publishHarness(
                runId,
                nodeName,
                HarnessEventType.BUDGET_EXHAUSTED,
                state,
                Map.of(
                        "reason", exception.reason().name(),
                        "observed", Long.toString(exception.observed()),
                        "limit", Long.toString(exception.limit()),
                        "consumedTokens", Long.toString(exception.consumedTokens())));
    }

    private void publishHarness(
            UUID runId,
            String nodeName,
            HarnessEventType eventType,
            AgentState state,
            Map<String, String> metadata) {
        harness.publish(new HarnessEvent(
                runId, nodeName, eventType, Instant.now(), state, metadata));
    }

    private static ExecutionBudget legacyBudget(int maxSteps) {
        return new ExecutionBudget(
                LEGACY_DURATION,
                LEGACY_DURATION,
                Long.MAX_VALUE,
                maxSteps,
                Integer.MAX_VALUE);
    }

    private String resolveNextNode(String source, AgentState state) {
        ConditionalTransition transition = conditionalEdges.get(source);
        if (transition != null) {
            String route = transition.condition().route(state);
            String target = transition.routes().get(route);
            if (target == null) {
                throw new IllegalStateException("条件路由未注册: " + route);
            }
            return target;
        }

        String target = edges.get(source);
        if (target == null) {
            throw new IllegalStateException("节点没有出边: " + source);
        }
        return target;
    }

    private void validateNodeName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("节点名称不能为空");
        }
        if (END.equals(name)) {
            throw new IllegalArgumentException("终点标识不能注册为节点");
        }
    }

    private void requireRegisteredNode(String name) {
        validateNodeName(name);
        if (!nodes.containsKey(name)) {
            throw new IllegalArgumentException("节点未注册: " + name);
        }
    }

    private void requireTarget(String target) {
        if (END.equals(target)) {
            return;
        }
        requireRegisteredNode(target);
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("状态图已经关闭");
        }
    }

    /**
     * 关闭虚拟线程执行器。
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.close();
        }
    }

    private record ConditionalTransition(Condition condition, Map<String, String> routes) {
    }

    private static final class ExecutionTracker {

        private final ExecutionBudget budget;
        private final long startedAtNanos;
        private final AtomicLong lastProgressNanos;
        private final AtomicLong consumedTokens = new AtomicLong();

        private ExecutionTracker(ExecutionBudget budget) {
            this.budget = budget;
            this.startedAtNanos = System.nanoTime();
            this.lastProgressNanos = new AtomicLong(startedAtNanos);
        }

        private void markProgress() {
            lastProgressNanos.set(System.nanoTime());
        }

        private long consumedTokens() {
            return consumedTokens.get();
        }

        private void recordNodeTokens(long base, long nodeTokens) {
            long total;
            try {
                total = Math.addExact(base, nodeTokens);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("图 token 累计值溢出", exception);
            }
            consumedTokens.set(total);
            if (total > budget.tokenBudget()) {
                throw failure(
                        ExecutionStopReason.TOKEN_BUDGET,
                        total,
                        budget.tokenBudget());
            }
        }

        private void checkAll(int steps, int noProgress) {
            checkActive();
            if (steps >= budget.maxSteps()) {
                throw new MaxStepsExceededException(
                        steps, budget.maxSteps(), consumedTokens());
            }
            if (noProgress >= budget.noProgressLimit()) {
                throw failure(
                        ExecutionStopReason.NO_PROGRESS,
                        noProgress,
                        budget.noProgressLimit());
            }
        }

        private void checkActive() {
            long now = System.nanoTime();
            long elapsed = nonNegativeDifference(now, startedAtNanos);
            long maxDuration = budget.maxDuration().toNanos();
            if (elapsed >= maxDuration) {
                throw durationFailure(
                        ExecutionStopReason.MAX_DURATION, elapsed, maxDuration);
            }
            long idle = nonNegativeDifference(now, lastProgressNanos.get());
            long idleTimeout = budget.idleTimeout().toNanos();
            if (idle >= idleTimeout) {
                throw durationFailure(
                        ExecutionStopReason.IDLE_TIMEOUT, idle, idleTimeout);
            }
            long tokens = consumedTokens();
            if (tokens > budget.tokenBudget()) {
                throw failure(
                        ExecutionStopReason.TOKEN_BUDGET,
                        tokens,
                        budget.tokenBudget());
            }
        }

        private long nextWaitNanos() {
            long now = System.nanoTime();
            long durationRemaining = budget.maxDuration().toNanos()
                    - nonNegativeDifference(now, startedAtNanos);
            long idleRemaining = budget.idleTimeout().toNanos()
                    - nonNegativeDifference(now, lastProgressNanos.get());
            return Math.max(1, Math.min(durationRemaining, idleRemaining));
        }

        private ExecutionBudgetExceededException durationFailure(
                ExecutionStopReason reason,
                long observedNanos,
                long limitNanos) {
            long observedMillis = Math.max(1, Duration.ofNanos(observedNanos).toMillis());
            long limitMillis = Math.max(1, Duration.ofNanos(limitNanos).toMillis());
            return failure(reason, observedMillis, limitMillis);
        }

        private ExecutionBudgetExceededException failure(
                ExecutionStopReason reason,
                long observed,
                long limit) {
            return new ExecutionBudgetExceededException(
                    reason, observed, limit, consumedTokens());
        }

        private long nonNegativeDifference(long current, long previous) {
            long difference = current - previous;
            return difference < 0 ? Long.MAX_VALUE : difference;
        }
    }

    private enum NoOpGraphExecutionListener implements GraphExecutionListener {
        INSTANCE;

        @Override
        public void onNodeStarted(String nodeName, AgentState state) {
        }

        @Override
        public void onNodeCompleted(String nodeName, String nextNode, AgentState state) {
        }
    }
}
