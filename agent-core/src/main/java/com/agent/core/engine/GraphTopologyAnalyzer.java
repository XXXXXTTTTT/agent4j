package com.agent.core.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 对已声明的节点和边做无副作用拓扑分析。 */
final class GraphTopologyAnalyzer {

    private GraphTopologyAnalyzer() {
    }

    static GraphTopology analyze(
            String entryPoint,
            Set<String> nodeNames,
            Map<String, Set<String>> outgoingTargets) {
        Objects.requireNonNull(nodeNames, "nodeNames 不能为空");
        Objects.requireNonNull(outgoingTargets, "outgoingTargets 不能为空");
        LinkedHashSet<String> orderedNodes = new LinkedHashSet<>(nodeNames);
        Map<String, Set<String>> orderedOutgoing = normalizeOutgoing(
                orderedNodes, outgoingTargets);

        Set<String> reachable = traverseForward(entryPoint, orderedNodes, orderedOutgoing);
        LinkedHashSet<String> unreachable = difference(orderedNodes, reachable);

        LinkedHashSet<String> deadEnds = new LinkedHashSet<>();
        for (String nodeName : orderedNodes) {
            if (orderedOutgoing.get(nodeName).isEmpty()) {
                deadEnds.add(nodeName);
            }
        }

        Set<String> canReachEnd = traverseReverseFromEnd(orderedNodes, orderedOutgoing);
        LinkedHashSet<String> withoutEndPath = difference(orderedNodes, canReachEnd);
        Set<String> cyclicNodes = new Tarjan(orderedNodes, orderedOutgoing).cyclicNodes();

        return new GraphTopology(
                entryPoint,
                orderedNodes,
                orderedOutgoing,
                unreachable,
                deadEnds,
                withoutEndPath,
                cyclicNodes);
    }

    private static Map<String, Set<String>> normalizeOutgoing(
            Set<String> nodeNames,
            Map<String, Set<String>> outgoingTargets) {
        Map<String, Set<String>> normalized = new LinkedHashMap<>();
        for (String nodeName : nodeNames) {
            Set<String> targets = outgoingTargets.get(nodeName);
            if (targets == null) {
                throw new IllegalArgumentException("outgoingTargets 缺少节点: " + nodeName);
            }
            normalized.put(nodeName, new LinkedHashSet<>(targets));
        }
        if (!outgoingTargets.keySet().equals(nodeNames)) {
            throw new IllegalArgumentException("outgoingTargets 必须精确覆盖 nodeNames");
        }
        return normalized;
    }

    private static Set<String> traverseForward(
            String entryPoint,
            Set<String> nodeNames,
            Map<String, Set<String>> outgoingTargets) {
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.push(entryPoint);
        while (!pending.isEmpty()) {
            String current = pending.pop();
            if (!nodeNames.contains(current) || !visited.add(current)) {
                continue;
            }
            List<String> targets = new ArrayList<>(outgoingTargets.get(current));
            for (int index = targets.size() - 1; index >= 0; index--) {
                String target = targets.get(index);
                if (!StateGraph.END.equals(target)) {
                    pending.push(target);
                }
            }
        }
        return visited;
    }

    private static Set<String> traverseReverseFromEnd(
            Set<String> nodeNames,
            Map<String, Set<String>> outgoingTargets) {
        Map<String, Set<String>> reverse = new LinkedHashMap<>();
        reverse.put(StateGraph.END, new LinkedHashSet<>());
        for (String nodeName : nodeNames) {
            reverse.put(nodeName, new LinkedHashSet<>());
        }
        for (Map.Entry<String, Set<String>> entry : outgoingTargets.entrySet()) {
            for (String target : entry.getValue()) {
                Set<String> predecessors = reverse.get(target);
                if (predecessors == null) {
                    throw new IllegalArgumentException("拓扑目标未注册: " + target);
                }
                predecessors.add(entry.getKey());
            }
        }

        LinkedHashSet<String> visited = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.push(StateGraph.END);
        while (!pending.isEmpty()) {
            String current = pending.pop();
            if (!visited.add(current)) {
                continue;
            }
            for (String predecessor : reverse.get(current)) {
                pending.push(predecessor);
            }
        }
        visited.remove(StateGraph.END);
        return visited;
    }

    private static LinkedHashSet<String> difference(
            Set<String> all,
            Set<String> included) {
        LinkedHashSet<String> difference = new LinkedHashSet<>(all);
        difference.removeAll(included);
        return difference;
    }

    private static final class Tarjan {

        private final Set<String> nodeNames;
        private final Map<String, Set<String>> outgoingTargets;
        private final Map<String, Integer> indices = new HashMap<>();
        private final Map<String, Integer> lowLinks = new HashMap<>();
        private final Deque<String> stack = new ArrayDeque<>();
        private final Set<String> onStack = new HashSet<>();
        private final LinkedHashSet<String> cyclicNodes = new LinkedHashSet<>();
        private int nextIndex;

        private Tarjan(
                Set<String> nodeNames,
                Map<String, Set<String>> outgoingTargets) {
            this.nodeNames = nodeNames;
            this.outgoingTargets = outgoingTargets;
        }

        private Set<String> cyclicNodes() {
            for (String nodeName : nodeNames) {
                if (!indices.containsKey(nodeName)) {
                    visit(nodeName);
                }
            }
            LinkedHashSet<String> ordered = new LinkedHashSet<>();
            for (String nodeName : nodeNames) {
                if (cyclicNodes.contains(nodeName)) {
                    ordered.add(nodeName);
                }
            }
            return ordered;
        }

        private void visit(String nodeName) {
            indices.put(nodeName, nextIndex);
            lowLinks.put(nodeName, nextIndex);
            nextIndex++;
            stack.push(nodeName);
            onStack.add(nodeName);

            for (String target : outgoingTargets.get(nodeName)) {
                if (StateGraph.END.equals(target)) {
                    continue;
                }
                if (!indices.containsKey(target)) {
                    visit(target);
                    lowLinks.put(
                            nodeName,
                            Math.min(lowLinks.get(nodeName), lowLinks.get(target)));
                } else if (onStack.contains(target)) {
                    lowLinks.put(
                            nodeName,
                            Math.min(lowLinks.get(nodeName), indices.get(target)));
                }
            }

            if (lowLinks.get(nodeName).equals(indices.get(nodeName))) {
                List<String> component = new ArrayList<>();
                String member;
                do {
                    member = stack.pop();
                    onStack.remove(member);
                    component.add(member);
                } while (!member.equals(nodeName));
                if (component.size() > 1
                        || outgoingTargets.get(nodeName).contains(nodeName)) {
                    cyclicNodes.addAll(component);
                }
            }
        }
    }
}
