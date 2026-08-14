package com.agent.web.orchestration;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.Node;
import com.agent.core.engine.NodeExecutionContext;
import com.agent.core.multiagent.AgentCatalog;
import com.agent.core.multiagent.AgentDescriptor;
import com.agent.core.multiagent.AgentHandoff;
import com.agent.core.multiagent.AgentHandoffExecutor;
import com.agent.core.multiagent.AgentHandoffResult;
import com.agent.core.multiagent.HandoffContextMode;
import com.agent.core.multiagent.HandoffExecutionContext;
import com.agent.core.nodes.CoderNode;
import com.agent.core.nodes.OpsNode;
import com.agent.core.nodes.PlannerNode;
import com.agent.core.nodes.ReviewerNode;
import com.agent.core.orchestration.OrchestrationMode;
import com.agent.core.orchestration.AgentRole;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 生产 code-agent 的多 Agent 协作节点包装器。
 *
 * <p>研究 Agent 只拥有证据输出键；工作区写入仍由现有 CoderNode 完成。
 * Reviewer 闭环使用 FRESH 上下文，避免把完整会话正文泄漏给独立审查子运行。</p>
 */
public final class ProductionMultiAgentOrchestrator implements AutoCloseable {

    public static final String MODE_KEY = "orchestration.mode";
    public static final String RESEARCH_SUMMARY_KEY = "orchestration.researchSummary";
    public static final String REVIEW_CONTEXT_MODE_KEY = "orchestration.reviewContextMode";
    public static final String MODEL_GROUP_KEY_PREFIX = "orchestration.modelGroup.";
    private static final String CODE_RESEARCH_OUTPUT = "research.codeEvidence";
    private static final String TEST_RESEARCH_OUTPUT = "research.testEvidence";
    private static final Duration HANDOFF_TIMEOUT = Duration.ofMinutes(5);

    private final AgentHandoffExecutor handoffExecutor;
    private volatile HandoffContextMode lastReviewContextMode;

    public ProductionMultiAgentOrchestrator(AgentHandoffExecutor handoffExecutor) {
        this.handoffExecutor = Objects.requireNonNull(handoffExecutor, "handoffExecutor 不能为空");
    }

    /** 返回生产目录，供 Spring 与 EGG 共享同一权限合同。 */
    public static AgentCatalog catalog() {
        return new AgentCatalog(List.of(
                new AgentDescriptor(
                        "coordinator", "multiagent-coordinator", Set.of(),
                        Set.of(RESEARCH_SUMMARY_KEY),
                        Set.of("researcher-code", "researcher-tests", "verifier")),
                new AgentDescriptor(
                        "researcher-code", "multiagent-research-code",
                        Set.of(PlannerNode.PLAN_KEY, CoderNode.WORKSPACE_PATH_KEY,
                                MODEL_GROUP_KEY_PREFIX + AgentRole.RESEARCHER.name()),
                        Set.of(CODE_RESEARCH_OUTPUT), Set.of()),
                new AgentDescriptor(
                        "researcher-tests", "multiagent-research-tests",
                        Set.of(PlannerNode.PLAN_KEY, CoderNode.WORKSPACE_PATH_KEY,
                                MODEL_GROUP_KEY_PREFIX + AgentRole.RESEARCHER.name()),
                        Set.of(TEST_RESEARCH_OUTPUT), Set.of()),
                new AgentDescriptor(
                        "verifier", "multiagent-verifier",
                        Set.of(CoderNode.UNIFIED_DIFF_KEY, OpsNode.STDOUT_KEY, OpsNode.STDERR_KEY,
                                MODEL_GROUP_KEY_PREFIX + AgentRole.VERIFIER.name()),
                        Set.of(ReviewerNode.APPROVED_KEY, ReviewerNode.SUMMARY_KEY,
                                ReviewerNode.FEEDBACK_KEY), Set.of())));
    }

    /** 返回串行生产图的固定节点顺序。 */
    public List<String> serialTopology() {
        return List.of("planner", "coder", "ops", "reviewer");
    }

    /** 在 Planner 与 Coder 之间执行只读研究子图。 */
    public Node researchNode() {
        return new Node() {
            @Override
            public AgentState execute(AgentState state) {
                return execute(NodeExecutionContext.current()
                        .orElseThrow(() -> new IllegalStateException("研究节点缺少 Run 上下文")), state);
            }

            @Override
            public AgentState execute(NodeExecutionContext context, AgentState state) {
                if (mode(state) != OrchestrationMode.PARALLEL_RESEARCH) {
                    return state;
                }
                requireReadableInputs(state, PlannerNode.PLAN_KEY, CoderNode.WORKSPACE_PATH_KEY);
                HandoffExecutionContext root = HandoffExecutionContext.root("coordinator", 2, 4);
                CompletableFuture<AgentHandoffResult> code = handoffExecutor.execute(
                        context.runId(), state, handoff(
                                "researcher-code", "收集代码结构与实现证据", HandoffContextMode.FORK,
                                Set.of(CODE_RESEARCH_OUTPUT)), root);
                CompletableFuture<AgentHandoffResult> tests = handoffExecutor.execute(
                        context.runId(), state, handoff(
                                "researcher-tests", "收集测试与验证证据", HandoffContextMode.FORK,
                                Set.of(TEST_RESEARCH_OUTPUT)), root);
                try {
                    CompletableFuture.allOf(code, tests).join();
                    AgentState merged = code.join().mergedParentState();
                    AgentState testMerged = tests.join().mergedParentState();
                    merged = merged.withVariable(TEST_RESEARCH_OUTPUT,
                            testMerged.variables().get(TEST_RESEARCH_OUTPUT));
                    String summary = merged.variables().get(CODE_RESEARCH_OUTPUT)
                            + "\n" + merged.variables().get(TEST_RESEARCH_OUTPUT);
                    return merged.withVariable(RESEARCH_SUMMARY_KEY, summary);
                } catch (CompletionException exception) {
                    throw new IllegalStateException("并行研究子运行失败", exception.getCause());
                }
            }
        };
    }

    /** 在 Ops 与结束之间执行独立 FRESH Reviewer；其他模式调用现有 Reviewer。 */
    public Node reviewNode(Node serialReviewer) {
        Objects.requireNonNull(serialReviewer, "serialReviewer 不能为空");
        return new Node() {
            @Override
            public AgentState execute(AgentState state) throws Exception {
                return serialReviewer.execute(state);
            }

            @Override
            public AgentState execute(NodeExecutionContext context, AgentState state) throws Exception {
                if (mode(state) != OrchestrationMode.REVIEW_LOOP) {
                    return serialReviewer.execute(context, withRoleModelGroup(
                            state, AgentRole.VERIFIER));
                }
                requireReadableInputs(state,
                        CoderNode.UNIFIED_DIFF_KEY, OpsNode.STDOUT_KEY, OpsNode.STDERR_KEY);
                lastReviewContextMode = HandoffContextMode.FRESH;
                AgentHandoffResult result = handoffExecutor.execute(
                        context.runId(), state,
                        handoff("verifier", "独立审查代码 Diff 与测试输出", HandoffContextMode.FRESH,
                                Set.of(ReviewerNode.APPROVED_KEY, ReviewerNode.SUMMARY_KEY,
                                        ReviewerNode.FEEDBACK_KEY)),
                        HandoffExecutionContext.root("coordinator", 2, 4)).join();
                return result.mergedParentState().withVariable(
                        REVIEW_CONTEXT_MODE_KEY, HandoffContextMode.FRESH.name());
            }
        };
    }

    /** 为现有生产节点绑定用户选择的角色模型组。 */
    public Node withRoleModelGroup(Node delegate, AgentRole role) {
        Objects.requireNonNull(delegate, "delegate 不能为空");
        Objects.requireNonNull(role, "role 不能为空");
        return new Node() {
            @Override
            public AgentState execute(AgentState state) throws Exception {
                return delegate.execute(withRoleModelGroup(state, role));
            }

            @Override
            public AgentState execute(NodeExecutionContext context, AgentState state)
                    throws Exception {
                return delegate.execute(context, withRoleModelGroup(state, role));
            }
        };
    }

    private AgentState withRoleModelGroup(AgentState state, AgentRole role) {
        String group = state.variables().get(MODEL_GROUP_KEY_PREFIX + role.name());
        return group == null || group.isBlank()
                ? state
                : state.withVariable("model.groupId", group);
    }

    public HandoffContextMode lastReviewContextMode() {
        return lastReviewContextMode;
    }

    private AgentHandoff handoff(
            String target,
            String content,
            HandoffContextMode contextMode,
            Set<String> outputs) {
        return new AgentHandoff(UUID.randomUUID(), "coordinator", target, content,
                contextMode, outputs, HANDOFF_TIMEOUT);
    }

    private OrchestrationMode mode(AgentState state) {
        String value = state.variables().get(MODE_KEY);
        return value == null || value.isBlank()
                ? OrchestrationMode.SERIAL_DEVELOPMENT
                : OrchestrationMode.valueOf(value);
    }

    private void requireReadableInputs(AgentState state, String... keys) {
        for (String key : keys) {
            if (!state.variables().containsKey(key)) {
                throw new IllegalStateException("多 Agent 编排缺少状态变量: " + key);
            }
        }
    }

    @Override
    public void close() {
        handoffExecutor.close();
    }
}
