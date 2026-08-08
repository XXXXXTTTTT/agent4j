package com.agent.core.tool;

import com.agent.core.intent.RequiredCapability;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolGovernanceTest {

    private static final UUID RUN_ID = UUID.fromString("70000000-0000-0000-0000-000000000010");

    @Test
    void authorizerUsesOnlyCapabilitiesAndApproval() {
        ToolAuthorizer authorizer = new DefaultToolAuthorizer();
        ToolInvocationContext context = context(Set.of(RequiredCapability.CODE_READ), false);
        ToolDefinition definition = definition("code.read", ToolRiskLevel.LOW,
                Set.of(RequiredCapability.CODE_READ));

        assertThat(authorizer.authorize(definition,
                new ToolCall("call-1", "code.read", JsonNodeFactory.instance.objectNode()), context))
                .isEqualTo(new ToolAuthorization(ToolAuthorizationDecision.ALLOWED, ""));

        ToolDefinition missing = definition("code.write", ToolRiskLevel.MEDIUM,
                Set.of(RequiredCapability.CODE_WRITE));
        ToolAuthorization denied = authorizer.authorize(missing,
                new ToolCall("call-2", "renamed.tool", JsonNodeFactory.instance.objectNode().put("secret", "x")),
                context);
        assertThat(denied.decision()).isEqualTo(ToolAuthorizationDecision.DENIED);
        assertThat(denied.reason()).contains("CODE_WRITE");

        ToolDefinition high = definition("terminal.exec", ToolRiskLevel.HIGH, Set.of());
        assertThat(authorizer.authorize(high,
                new ToolCall("call-3", "terminal.exec", JsonNodeFactory.instance.objectNode()), context))
                .isEqualTo(new ToolAuthorization(ToolAuthorizationDecision.APPROVAL_REQUIRED,
                        "HIGH 风险工具需要人工审批"));
        assertThat(authorizer.authorize(high,
                new ToolCall("call-4", "terminal.exec", JsonNodeFactory.instance.objectNode()),
                context(Set.of(), true)))
                .isEqualTo(new ToolAuthorization(ToolAuthorizationDecision.ALLOWED, ""));
    }

    @Test
    void auditEventValidatesAndFreezesAllFields() {
        ToolAuditEvent event = new ToolAuditEvent(
                RUN_ID,
                "ops",
                "user-a",
                "call-1",
                "terminal.exec",
                Optional.of(ToolRiskLevel.HIGH),
                ToolResultStatus.TIMED_OUT,
                12,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "ToolTimeoutException",
                true);

        assertThat(event.runId()).isEqualTo(RUN_ID);
        assertThat(event.nodeName()).isEqualTo("ops");
        assertThat(event.userId()).isEqualTo("user-a");
        assertThat(event.callId()).isEqualTo("call-1");
        assertThat(event.toolName()).isEqualTo("terminal.exec");
        assertThat(event.riskLevel()).contains(ToolRiskLevel.HIGH);
        assertThat(event.status()).isEqualTo(ToolResultStatus.TIMED_OUT);
        assertThat(event.durationMs()).isEqualTo(12);
        assertThat(event.cancellationRequested()).isTrue();
    }

    @Test
    void auditEventAllowsEmptyRiskOnlyForUnknownToolFailure() {
        new ToolAuditEvent(RUN_ID, "planner", "user-a", "call-2", "unknown.tool",
                Optional.empty(), ToolResultStatus.FAILED, 0,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "ToolNotFoundException", false);

        assertThatThrownBy(() -> new ToolAuditEvent(RUN_ID, "ops", "user-a", "call-3", "tool.exec",
                Optional.empty(), ToolResultStatus.FAILED, 0,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "IllegalStateException", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolAuditEvent(RUN_ID, "ops", "user-a", "call-4", "tool.exec",
                Optional.of(ToolRiskLevel.LOW), ToolResultStatus.SUCCEEDED, 0,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "IllegalStateException", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolAuditEvent(RUN_ID, "ops", "user-a", "call-5", "tool.exec",
                Optional.of(ToolRiskLevel.LOW), ToolResultStatus.FAILED, -1,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "IllegalStateException", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolAuditEvent(RUN_ID, "ops", "user-a", "call-6", "tool.exec",
                Optional.of(ToolRiskLevel.LOW), ToolResultStatus.FAILED, 0,
                "ABC3456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "IllegalStateException", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noopSinkValidatesEvent() {
        ToolAuditSink.noop().record(new ToolAuditEvent(RUN_ID, "ops", "user-a", "call-7", "tool.exec",
                Optional.of(ToolRiskLevel.LOW), ToolResultStatus.SUCCEEDED, 0,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", "", false));
        assertThatThrownBy(() -> ToolAuditSink.noop().record(null))
                .isInstanceOf(NullPointerException.class);
    }

    private ToolInvocationContext context(Set<RequiredCapability> capabilities, boolean approved) {
        return new ToolInvocationContext(RUN_ID, "planner", "user-a", java.nio.file.Path.of("."), capabilities,
                approved);
    }

    private ToolDefinition definition(String name, ToolRiskLevel risk, Set<RequiredCapability> capabilities) {
        return new ToolDefinition(name, "测试工具", JsonNodeFactory.instance.objectNode().put("type", "object"),
                capabilities, risk, Duration.ofSeconds(1), (call, context) -> JsonNodeFactory.instance.objectNode());
    }
}
