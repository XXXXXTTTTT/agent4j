package com.agent.core.engine;

import java.util.Objects;
import java.util.function.Consumer;

/** 在父图节点中执行一个具有独立状态的严格校验子图。 */
public final class SubgraphNode implements Node {

    private final String subgraphId;
    private final GraphFactory graphFactory;
    private final SubgraphStateBridge stateBridge;

    /** 创建显式状态桥接的子图节点。 */
    public SubgraphNode(
            String subgraphId,
            GraphFactory graphFactory,
            SubgraphStateBridge stateBridge) {
        this.subgraphId = requireText(subgraphId, "subgraphId");
        this.graphFactory = Objects.requireNonNull(graphFactory, "graphFactory 不能为空");
        this.stateBridge = Objects.requireNonNull(stateBridge, "stateBridge 不能为空");
    }

    /** 返回子图精确标识。 */
    public String subgraphId() {
        return subgraphId;
    }

    /** 使用当前线程已绑定上下文执行；上下文外调用会被拒绝。 */
    @Override
    public AgentState execute(AgentState state) throws Exception {
        NodeExecutionContext context = NodeExecutionContext.current()
                .orElseThrow(() -> new IllegalStateException("当前没有节点执行上下文"));
        return execute(context, state);
    }

    /** 投影状态、校验并执行独立子图，再合并结果。 */
    @Override
    public AgentState execute(NodeExecutionContext context, AgentState parentState)
            throws Exception {
        Objects.requireNonNull(context, "context 不能为空");
        Objects.requireNonNull(parentState, "parentState 不能为空");
        Consumer<String> parentProgress = NodeExecutionContext.progressReporter();
        parentProgress.accept("subgraph:" + subgraphId + ":started");

        AgentState childState = Objects.requireNonNull(
                stateBridge.project(parentState), "状态桥 project 返回值不能为空");
        StateGraph childGraph = Objects.requireNonNull(
                graphFactory.create(), "graphFactory 返回值不能为空");
        try (childGraph) {
            childGraph.validateTopology();
            GraphExecutionResult result = childGraph.execute(
                    new GraphExecutionRequest(
                            context.runId(), childState, childGraph.entryPoint(), false),
                    childListener(parentProgress));
            if (result instanceof GraphExecutionResult.Interrupted interrupted) {
                throw new SubgraphInterruptedException(
                        subgraphId,
                        interrupted.nodeName(),
                        interrupted.request());
            }
            AgentState completed = ((GraphExecutionResult.Completed) result).state();
            AgentState merged = Objects.requireNonNull(
                    stateBridge.merge(parentState, completed),
                    "状态桥 merge 返回值不能为空");
            parentProgress.accept("subgraph:" + subgraphId + ":completed");
            return merged;
        } catch (GraphTopologyException | SubgraphInterruptedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SubgraphExecutionException(subgraphId, exception);
        }
    }

    private GraphExecutionListener childListener(Consumer<String> parentProgress) {
        return new GraphExecutionListener() {
            @Override
            public void onNodeStarted(String nodeName, AgentState state) {
                parentProgress.accept("subgraph:" + subgraphId
                        + ":node:" + nodeName + ":started");
            }

            @Override
            public void onNodeProgress(String nodeName, String summary) {
                parentProgress.accept("subgraph:" + subgraphId
                        + ":node:" + nodeName + ":" + summary);
            }

            @Override
            public void onNodeCompleted(String nodeName, String nextNode, AgentState state) {
                parentProgress.accept("subgraph:" + subgraphId
                        + ":node:" + nodeName + ":completed:" + nextNode);
            }
        };
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }
}
