package com.agent.web.profile;

import com.agent.core.engine.ExecutionBudget;
import com.agent.core.engine.GraphNotFoundException;
import com.agent.core.engine.GraphTopology;
import com.agent.core.engine.StateGraph;
import com.agent.core.llm.TaskType;
import com.agent.core.profile.AgentProfile;
import com.agent.core.profile.AgentProfileNotFoundException;
import com.agent.core.profile.AgentProfileRegistry;
import com.agent.core.profile.AgentProfileSnapshot;
import com.agent.web.controller.RunExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest(controllers = AgentProfileController.class)
@Import(RunExceptionHandler.class)
class AgentProfileControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private AgentProfileRegistry registry;

    @Test
    void listsProfilesWithoutCreatingGraphs() {
        AgentProfile profile = profile();
        when(registry.profileIds()).thenReturn(Set.of("code-agent"));
        when(registry.get("code-agent")).thenReturn(profile);

        webTestClient.get()
                .uri("/api/agent-profiles")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$[0].profileId").isEqualTo("code-agent")
                .jsonPath("$[0].graphId").isEqualTo("code-agent")
                .jsonPath("$[0].taskTypes[0]").isEqualTo("CODE")
                .jsonPath("$[0].capabilities[0]").isEqualTo("AstService")
                .jsonPath("$[0].executionBudget.tokenBudget").isEqualTo(1000);

        verify(registry, never()).inspect("code-agent");
    }

    @Test
    void returnsProfileDetailAndTopologyForExactId() {
        AgentProfile profile = profile();
        GraphTopology topology = topology();
        when(registry.inspect("code-agent"))
                .thenReturn(new AgentProfileSnapshot(profile, topology));

        webTestClient.get()
                .uri("/api/agent-profiles/{profileId}", "code-agent")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.profileId").isEqualTo("code-agent")
                .jsonPath("$.graphId").isEqualTo("code-agent")
                .jsonPath("$.topology.entryPoint").isEqualTo("planner")
                .jsonPath("$.topology.nodeNames[0]").isEqualTo("planner")
                .jsonPath("$.topology.outgoingTargets.planner[0]")
                .isEqualTo("__END__");
    }

    @Test
    void returnsTopologyOnlyForExactId() {
        when(registry.inspect("code-agent"))
                .thenReturn(new AgentProfileSnapshot(profile(), topology()));

        webTestClient.get()
                .uri("/api/agent-profiles/{profileId}/topology", "code-agent")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.entryPoint").isEqualTo("planner")
                .jsonPath("$.unreachableNodes").isEmpty()
                .jsonPath("$.nodesWithoutEndPath").isEmpty();
    }

    @Test
    void mapsUnknownProfileAndGraphToNotFound() {
        when(registry.inspect("missing-profile"))
                .thenThrow(new AgentProfileNotFoundException("missing-profile"));
        when(registry.inspect("missing-graph"))
                .thenThrow(new GraphNotFoundException("missing-graph"));

        webTestClient.get()
                .uri("/api/agent-profiles/{profileId}", "missing-profile")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Agent Profile 未注册: missing-profile")
                .jsonPath("$.instance").isEqualTo("/api/agent-profiles/missing-profile");

        webTestClient.get()
                .uri("/api/agent-profiles/{profileId}/topology", "missing-graph")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("图未注册: missing-graph");
    }

    @Test
    void rejectsBlankProfileId() {
        webTestClient.get()
                .uri("/api/agent-profiles/{profileId}", " ")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("profileId 不能为空");
    }

    private AgentProfile profile() {
        return new AgentProfile(
                "code-agent",
                "code-agent",
                "Code Agent",
                "生产代码 Agent",
                Set.of(TaskType.CODE),
                Set.of("AstService"),
                new ExecutionBudget(Duration.ofSeconds(30), Duration.ofSeconds(5), 1_000, 4, 2));
    }

    private GraphTopology topology() {
        return new GraphTopology(
                "planner",
                Set.of("planner"),
                Map.of("planner", Set.of(StateGraph.END)),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of());
    }
}
