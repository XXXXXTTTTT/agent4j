package com.agent.core.engine;

import java.util.Objects;

/** 状态图未通过严格拓扑校验。 */
public final class GraphTopologyException extends IllegalStateException {

    private final GraphTopology topology;

    public GraphTopologyException(GraphTopology topology) {
        super(message(Objects.requireNonNull(topology, "topology 不能为空")));
        this.topology = topology;
    }

    public GraphTopology topology() {
        return topology;
    }

    private static String message(GraphTopology topology) {
        return "状态图拓扑无效: unreachableNodes=" + topology.unreachableNodes()
                + ", deadEndNodes=" + topology.deadEndNodes()
                + ", nodesWithoutEndPath=" + topology.nodesWithoutEndPath();
    }
}
