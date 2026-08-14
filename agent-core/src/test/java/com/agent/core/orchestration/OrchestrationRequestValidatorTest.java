package com.agent.core.orchestration;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrchestrationRequestValidatorTest {

    @Test
    void exposesExactlyTheSupportedModes() {
        assertThat(OrchestrationMode.values())
                .containsExactly(
                        OrchestrationMode.SERIAL_DEVELOPMENT,
                        OrchestrationMode.PARALLEL_RESEARCH,
                        OrchestrationMode.REVIEW_LOOP);
    }

    @Test
    void exposesExactlyTheSupportedRoles() {
        assertThat(AgentRole.values())
                .containsExactly(
                        AgentRole.COORDINATOR,
                        AgentRole.RESEARCHER,
                        AgentRole.IMPLEMENTER,
                        AgentRole.VERIFIER);
    }

    @Test
    void resolvesEachRoleFromThePrimaryModelGroupWhenNoRoleOverrideExists() {
        OrchestrationRequest request = new OrchestrationRequest(
                OrchestrationMode.PARALLEL_RESEARCH, Map.of());

        assertThat(OrchestrationRequestValidator.validate(request, "group-primary"))
                .containsEntry(AgentRole.COORDINATOR, "group-primary")
                .containsEntry(AgentRole.RESEARCHER, "group-primary")
                .containsEntry(AgentRole.IMPLEMENTER, "group-primary")
                .containsEntry(AgentRole.VERIFIER, "group-primary");
    }

    @Test
    void rejectsBlankRoleModelGroupInsteadOfSilentlyFallingBack() {
        OrchestrationRequest request = new OrchestrationRequest(
                OrchestrationMode.REVIEW_LOOP,
                Map.of(AgentRole.VERIFIER, " "));

        assertThatThrownBy(() -> OrchestrationRequestValidator.validate(request, "group-primary"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VERIFIER");
    }

    @Test
    void appliesRoleOverrideWithoutChangingOtherRoleFallbacks() {
        OrchestrationRequest request = new OrchestrationRequest(
                OrchestrationMode.SERIAL_DEVELOPMENT,
                Map.of(AgentRole.IMPLEMENTER, "group-implementer"));

        assertThat(OrchestrationRequestValidator.validate(request, "group-primary"))
                .containsEntry(AgentRole.IMPLEMENTER, "group-implementer")
                .containsEntry(AgentRole.COORDINATOR, "group-primary")
                .containsEntry(AgentRole.RESEARCHER, "group-primary")
                .containsEntry(AgentRole.VERIFIER, "group-primary");
    }

    @Test
    void rejectsUnknownRoleKeyAtContractBoundary() {
        @SuppressWarnings({"rawtypes", "unchecked"})
        Map rawGroups = Map.of("UNKNOWN", "group-unknown");

        assertThatThrownBy(() -> new OrchestrationRequest(
                OrchestrationMode.REVIEW_LOOP, rawGroups))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知角色键");
    }

    @Test
    void rejectsUnknownModeTextBeforeCreatingContract() {
        assertThatThrownBy(() -> OrchestrationMode.valueOf("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankPrimaryModelGroup() {
        OrchestrationRequest request = new OrchestrationRequest(
                OrchestrationMode.SERIAL_DEVELOPMENT, Map.of());

        assertThatThrownBy(() -> OrchestrationRequestValidator.validate(request, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("主模型组");
    }

    @Test
    void returnsAnImmutableResolvedMap() {
        OrchestrationRequest request = new OrchestrationRequest(
                OrchestrationMode.REVIEW_LOOP, Map.of());
        Map<AgentRole, String> resolved = OrchestrationRequestValidator.validate(request, "group-primary");

        assertThatThrownBy(() -> resolved.put(AgentRole.VERIFIER, "other"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
