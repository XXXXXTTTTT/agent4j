package com.agent.web.config;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.ExecutionBudget;
import com.agent.core.engine.GraphFactory;
import com.agent.core.engine.StateGraph;
import com.agent.core.profile.AgentProfile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Set;

/** 提供 Docker 快速体验使用的显式演示图。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "agent.sample.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SampleGraphConfiguration {

    private static final Duration LEGACY_DURATION = Duration.ofDays(3650);
    private static final ExecutionBudget DEMO_BUDGET = new ExecutionBudget(
            LEGACY_DURATION, LEGACY_DURATION, Long.MAX_VALUE, 4, Integer.MAX_VALUE);
    private static final ExecutionBudget SAMPLE_BUDGET = new ExecutionBudget(
            LEGACY_DURATION, LEGACY_DURATION, Long.MAX_VALUE, 1, Integer.MAX_VALUE);

    /** 注册面向用户快速体验的四阶段 Agent 图。 */
    @Bean("demo-agent")
    GraphFactory demoGraph() {
        return () -> new StateGraph(4)
                .addNode("planner", (state) -> state
                        .withVariable("planner.plan", "分析任务 -> 修改代码 -> 运行测试 -> 审查结果")
                        .withVariable("planner.status", "计划已生成")
                        .withTraceEntry("planner"))
                .addNode("coder", (state) -> state
                        .withVariable("coder.unifiedDiff", demoDiff())
                        .withVariable("coder.updatedFiles", "demo/AgentTask.java")
                        .withVariable("coder.status", "已生成代码变更")
                        .withTraceEntry("coder"))
                .addNode("ops", (state) -> state
                        .withVariable("ops.command", "printf 'Agent Demo 测试通过\\n'")
                        .withVariable("ops.exitCode", "0")
                        .withVariable("ops.stdout", "Agent Demo 测试通过\n")
                        .withVariable("ops.stderr", "")
                        .withVariable("ops.timedOut", "false")
                        .withVariable("ops.status", "测试通过")
                        .withTraceEntry("ops"))
                .addNode("reviewer", (state) -> state
                        .withVariable("reviewer.approved", "true")
                        .withVariable("reviewer.summary", "任务链路已完成")
                        .withVariable("reviewer.feedback", "代码变更和测试结果均符合演示流程")
                        .withTraceEntry("reviewer"))
                .setEntryPoint("planner")
                .addEdge("planner", "coder")
                .addEdge("coder", "ops")
                .addEdge("ops", "reviewer")
                .addEdge("reviewer", StateGraph.END);
    }

    /** 声明精确关联 `demo-agent` 图的演示 Profile。 */
    @Bean
    AgentProfile demoAgentProfile() {
        return new AgentProfile(
                "demo-agent",
                "demo-agent",
                "Agent4J 演示 Agent",
                "展示 planner、coder、ops、reviewer 四阶段执行链",
                Set.of(),
                Set.of(),
                DEMO_BUDGET);
    }

    /** 注册精确 graphId 为 sample 的最小可运行图。 */
    @Bean("sample")
    GraphFactory sampleGraph() {
        return () -> new StateGraph(1)
                .addNode("sample", (AgentState state) ->
                        state.withVariable("sample.status", "ready"))
                .setEntryPoint("sample")
                .addEdge("sample", StateGraph.END);
    }

    /** 声明精确关联 `sample` 图的最小 Profile。 */
    @Bean
    AgentProfile sampleAgentProfile() {
        return new AgentProfile(
                "sample",
                "sample",
                "Agent4J 最小示例",
                "展示单节点状态图的最小运行结构",
                Set.of(),
                Set.of(),
                SAMPLE_BUDGET);
    }

    private String demoDiff() {
        return """
                diff --git a/demo/AgentTask.java b/demo/AgentTask.java
                --- a/demo/AgentTask.java
                +++ b/demo/AgentTask.java
                @@ -1 +1 @@
                -class AgentTask {}
                +class AgentTask { String status = "ready"; }
                """;
    }
}
