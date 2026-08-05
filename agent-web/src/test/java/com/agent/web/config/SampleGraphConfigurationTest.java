package com.agent.web.config;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.GraphFactory;
import com.agent.core.engine.StateGraph;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SampleGraphConfigurationTest {

    @Test
    void createsTheTaskFirstDemoAgentWithVisibleFourStageArtifacts() {
        GraphFactory factory = new SampleGraphConfiguration().demoGraph();

        try (StateGraph graph = factory.create()) {
            AgentState state = AgentState.empty()
                    .withVariable("demo.task", "修复登录超时")
                    .withVariable("demo.workspace", "当前工作区");
            AgentState result = graph.execute(state);

            assertThat(graph.entryPoint()).isEqualTo("planner");
            assertThat(result.trace()).containsExactly("planner", "coder", "ops", "reviewer");
            assertThat(result.variables())
                    .containsEntry("demo.task", "修复登录超时")
                    .containsEntry("planner.plan", "分析任务 -> 修改代码 -> 运行测试 -> 审查结果")
                    .containsKey("coder.unifiedDiff")
                    .containsEntry("ops.exitCode", "0")
                    .containsEntry("ops.stdout", "Agent Demo 测试通过\n")
                    .containsEntry("reviewer.approved", "true")
                    .containsEntry("reviewer.summary", "任务链路已完成");
        }
    }
}
