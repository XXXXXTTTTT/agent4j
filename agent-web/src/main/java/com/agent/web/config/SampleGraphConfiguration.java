package com.agent.web.config;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.GraphFactory;
import com.agent.core.engine.StateGraph;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 提供 Docker 快速体验使用的显式演示图。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "agent.sample.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SampleGraphConfiguration {

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

    /** 注册精确 graphId 为 sample 的最小可运行图。 */
    @Bean("sample")
    GraphFactory sampleGraph() {
        return () -> new StateGraph(1)
                .addNode("sample", (AgentState state) ->
                        state.withVariable("sample.status", "ready"))
                .setEntryPoint("sample")
                .addEdge("sample", StateGraph.END);
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
