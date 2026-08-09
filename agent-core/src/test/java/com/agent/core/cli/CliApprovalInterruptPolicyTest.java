package com.agent.core.cli;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.InterruptRequest;
import com.agent.core.intent.RequiredCapability;
import com.agent.core.nodes.CoderNode;
import com.agent.core.nodes.OpsNode;
import com.agent.core.nodes.PlannerNode;
import com.agent.sandbox.pty.PtyTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CliApprovalInterruptPolicyTest {

    private static final UUID RUN_ID = UUID.fromString(
            "b4f8a280-0505-4ee9-8f1b-f4754f8d411b");

    @TempDir
    Path workspace;

    private PtyTarget target;
    private ObjectMapper objectMapper;

    @BeforeEach
    void createTarget() throws Exception {
        target = new PtyTarget(Files.createFile(workspace.resolve("bash.exe")), workspace);
        objectMapper = new ObjectMapper();
    }

    @Test
    void allowsReadOnlyCommandWithoutInterrupt() {
        CliApprovalInterruptPolicy policy = policy(CliRiskLevel.READ_ONLY);

        Optional<InterruptRequest> result = policy.evaluate(
                RUN_ID, "ops", state("test.read", List.of("value.txt")));

        assertThat(result).isEmpty();
        assertThat(policy.authorizeForExecution(
                state("test.read", List.of("value.txt")), false)
                .decision()).isEqualTo(CliAuthorizationDecision.ALLOWED);
    }

    @Test
    void interruptsMutatingCommandAndReusesApprovedPlanOnResume() {
        CliApprovalInterruptPolicy policy = policy(CliRiskLevel.MUTATING);
        AgentState state = state("test.write", List.of("value.txt"));

        InterruptRequest interrupt = policy.evaluate(RUN_ID, "ops", state).orElseThrow();
        CliAuthorization approved = policy.authorizeForExecution(state, true);

        assertThat(interrupt.nodeName()).isEqualTo("ops");
        assertThat(interrupt.reason()).isEqualTo("等待用户批准");
        assertThat(interrupt.details())
                .containsEntry("commandName", "test.write")
                .containsEntry("commandArguments", "[\"value.txt\"]")
                .containsEntry("command", "'printf' 'value.txt'")
                .containsEntry("riskLevel", "MUTATING")
                .containsEntry("authorizationReason", "等待用户批准");
        assertThat(interrupt.details().get("commandSha256")).hasSize(64);
        assertThat(approved.decision()).isEqualTo(CliAuthorizationDecision.ALLOWED);
        assertThat(approved.reason()).isEqualTo("用户已批准");
        assertThat(approved.plan().commandSha256())
                .isEqualTo(interrupt.details().get("commandSha256"));
    }

    @Test
    void rejectsArgumentInjectionAndWorkspaceEscapeBeforeInterrupt() throws Exception {
        CliApprovalInterruptPolicy policy = policy(CliRiskLevel.READ_ONLY);
        AgentState injected = state("test.read", List.of("value.txt;rm"));
        Path outside = Files.createTempDirectory("cli-policy-outside-");
        PtyTarget outsideTarget = new PtyTarget(
                Files.createFile(outside.resolve("bash.exe")), outside);
        CliApprovalInterruptPolicy escaped = new CliApprovalInterruptPolicy(
                catalog("test.read", CliRiskLevel.READ_ONLY),
                outsideTarget,
                Duration.ofSeconds(30),
                objectMapper);

        assertThatThrownBy(() -> policy.evaluate(RUN_ID, "ops", injected))
                .isInstanceOf(CliArgumentException.class);
        assertThatThrownBy(() -> escaped.evaluate(
                RUN_ID, "ops", state("test.read", List.of("value.txt"))))
                .isInstanceOf(CliWorkspaceViolationException.class);
    }

    private CliApprovalInterruptPolicy policy(CliRiskLevel riskLevel) {
        String name = riskLevel == CliRiskLevel.MUTATING ? "test.write" : "test.read";
        return new CliApprovalInterruptPolicy(
                catalog(name, riskLevel), target, Duration.ofSeconds(30), objectMapper);
    }

    private CliCommandCatalog catalog(String name, CliRiskLevel riskLevel) {
        return new CliCommandCatalog(List.of(new CliCommandDefinition(
                name,
                "printf",
                List.of(),
                riskLevel,
                Set.of(RequiredCapability.TERMINAL))));
    }

    private AgentState state(String name, List<String> arguments) {
        try {
            return AgentState.empty()
                    .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString())
                    .withVariable(OpsNode.COMMAND_NAME_KEY, name)
                    .withVariable(OpsNode.COMMAND_ARGUMENTS_KEY,
                            objectMapper.writeValueAsString(arguments))
                    .withVariable(PlannerNode.REQUIRED_CAPABILITIES_KEY, "TERMINAL");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
