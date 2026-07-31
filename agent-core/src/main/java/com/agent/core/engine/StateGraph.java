package com.agent.core.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 使用 Java 21 虚拟线程逐节点驱动的状态图。
 */
public final class StateGraph implements AutoCloseable {

    /** 图的唯一终点标识。 */
    public static final String END = "__END__";

    private final int maxSteps;
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
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps 必须大于 0");
        }
        this.maxSteps = maxSteps;
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

        String currentNode = entryPoint;
        AgentState currentState = initialState;
        int steps = 0;

        while (!END.equals(currentNode)) {
            if (steps >= maxSteps) {
                throw new MaxStepsExceededException(maxSteps);
            }
            currentState = executeNode(currentNode, currentState);
            steps++;
            currentNode = resolveNextNode(currentNode, currentState);
        }
        return currentState;
    }

    private AgentState executeNode(String nodeName, AgentState state) {
        Node node = nodes.get(nodeName);
        if (node == null) {
            throw new IllegalStateException("节点未注册: " + nodeName);
        }

        Future<AgentState> future = executor.submit(() -> node.execute(state));
        try {
            AgentState result = future.get();
            if (result == null) {
                throw new NullPointerException("节点返回状态不能为空");
            }
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GraphExecutionException(nodeName, exception);
        } catch (ExecutionException exception) {
            throw new GraphExecutionException(nodeName, exception.getCause());
        } catch (RuntimeException exception) {
            throw new GraphExecutionException(nodeName, exception);
        }
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
}
