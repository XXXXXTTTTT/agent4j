package com.agent.core.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 状态图声明结构的不可变拓扑快照。 */
public record GraphTopology(
        String entryPoint,
        Set<String> nodeNames,
        Map<String, Set<String>> outgoingTargets,
        Set<String> unreachableNodes,
        Set<String> deadEndNodes,
        Set<String> nodesWithoutEndPath,
        Set<String> cyclicNodes) {

    public GraphTopology {
        requireText(entryPoint, "entryPoint");
        nodeNames = freezeTextSet(nodeNames, "nodeNames");
        if (nodeNames.isEmpty()) {
            throw new IllegalArgumentException("nodeNames 不能为空集合");
        }
        if (!nodeNames.contains(entryPoint)) {
            throw new IllegalArgumentException("entryPoint 不在 nodeNames 中: " + entryPoint);
        }
        outgoingTargets = freezeOutgoing(outgoingTargets, nodeNames);
        unreachableNodes = freezeNodeSubset(unreachableNodes, nodeNames, "unreachableNodes");
        deadEndNodes = freezeNodeSubset(deadEndNodes, nodeNames, "deadEndNodes");
        nodesWithoutEndPath = freezeNodeSubset(
                nodesWithoutEndPath, nodeNames, "nodesWithoutEndPath");
        cyclicNodes = freezeNodeSubset(cyclicNodes, nodeNames, "cyclicNodes");
    }

    /** 仅当所有严格结构违规集合为空时返回 true。 */
    public boolean valid() {
        return unreachableNodes.isEmpty()
                && deadEndNodes.isEmpty()
                && nodesWithoutEndPath.isEmpty();
    }

    private static Map<String, Set<String>> freezeOutgoing(
            Map<String, Set<String>> outgoingTargets,
            Set<String> nodeNames) {
        Objects.requireNonNull(outgoingTargets, "outgoingTargets 不能为空");
        if (!outgoingTargets.keySet().equals(nodeNames)) {
            throw new IllegalArgumentException("outgoingTargets 必须精确覆盖 nodeNames");
        }
        Map<String, Set<String>> checked = new LinkedHashMap<>();
        for (String nodeName : nodeNames) {
            Set<String> targets = freezeTextSet(
                    outgoingTargets.get(nodeName), "outgoingTargets[" + nodeName + "]");
            for (String target : targets) {
                if (!StateGraph.END.equals(target) && !nodeNames.contains(target)) {
                    throw new IllegalArgumentException("拓扑目标未注册: " + target);
                }
            }
            checked.put(nodeName, targets);
        }
        return Collections.unmodifiableMap(checked);
    }

    private static Set<String> freezeNodeSubset(
            Set<String> values,
            Set<String> nodeNames,
            String field) {
        Set<String> checked = freezeTextSet(values, field);
        if (!nodeNames.containsAll(checked)) {
            Set<String> unknown = new LinkedHashSet<>(checked);
            unknown.removeAll(nodeNames);
            throw new IllegalArgumentException(field + " 包含未注册节点: " + unknown);
        }
        return checked;
    }

    private static Set<String> freezeTextSet(Set<String> values, String field) {
        Objects.requireNonNull(values, field + " 不能为空");
        LinkedHashSet<String> checked = new LinkedHashSet<>();
        for (String value : values) {
            requireText(value, field);
            checked.add(value);
        }
        return Collections.unmodifiableSet(checked);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}
