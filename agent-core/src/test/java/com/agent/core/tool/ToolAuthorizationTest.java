package com.agent.core.tool;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolAuthorizationTest {

    @Test
    void exposesExactRiskResultAndAuthorizationDecisions() {
        assertThat(ToolRiskLevel.values())
                .containsExactly(ToolRiskLevel.LOW, ToolRiskLevel.MEDIUM, ToolRiskLevel.HIGH);
        assertThat(ToolResultStatus.values()).containsExactly(
                ToolResultStatus.SUCCEEDED,
                ToolResultStatus.DENIED,
                ToolResultStatus.APPROVAL_REQUIRED,
                ToolResultStatus.TIMED_OUT,
                ToolResultStatus.FAILED);
        assertThat(ToolAuthorizationDecision.values()).containsExactly(
                ToolAuthorizationDecision.ALLOWED,
                ToolAuthorizationDecision.DENIED,
                ToolAuthorizationDecision.APPROVAL_REQUIRED);
    }

    @Test
    void validatesAuthorizationReasonByDecision() {
        assertThat(new ToolAuthorization(ToolAuthorizationDecision.ALLOWED, "").reason())
                .isEmpty();
        assertThat(new ToolAuthorization(
                ToolAuthorizationDecision.DENIED, "缺少 CODE_WRITE").reason())
                .isEqualTo("缺少 CODE_WRITE");

        assertThatThrownBy(() -> new ToolAuthorization(
                ToolAuthorizationDecision.DENIED, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
        assertThatThrownBy(() -> new ToolAuthorization(
                ToolAuthorizationDecision.APPROVAL_REQUIRED, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
        assertThatThrownBy(() -> new ToolAuthorization(null, ""))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("decision");
    }

    @Test
    void preservesTypedExceptionFieldsAndCauses() {
        IllegalStateException cause = new IllegalStateException("root cause");

        ToolRegistrationException registration = new ToolRegistrationException(
                "code.read", "工具已注册", cause);
        ToolNotFoundException notFound = new ToolNotFoundException("missing.tool");
        ToolSchemaException schema = new ToolSchemaException(
                "/properties/path/type", "type 不受支持", cause);
        ToolAuthorizationException denied = new ToolAuthorizationException(
                "code.write", "缺少 CODE_WRITE");
        ToolApprovalRequiredException approval = new ToolApprovalRequiredException(
                "terminal.exec", "HIGH 风险工具需要审批");
        ToolTimeoutException timeout = new ToolTimeoutException(
                "terminal.exec", Duration.ofSeconds(5));

        assertThat(registration.toolName()).isEqualTo("code.read");
        assertThat(registration).hasCause(cause);
        assertThat(notFound.toolName()).isEqualTo("missing.tool");
        assertThat(schema.jsonPointer()).isEqualTo("/properties/path/type");
        assertThat(schema).hasCause(cause);
        assertThat(denied.toolName()).isEqualTo("code.write");
        assertThat(denied.reason()).isEqualTo("缺少 CODE_WRITE");
        assertThat(approval.toolName()).isEqualTo("terminal.exec");
        assertThat(approval.reason()).isEqualTo("HIGH 风险工具需要审批");
        assertThat(timeout.toolName()).isEqualTo("terminal.exec");
        assertThat(timeout.timeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void rejectsBlankTypedExceptionFields() {
        assertThatThrownBy(() -> new ToolNotFoundException(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolSchemaException("", "invalid", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolAuthorizationException("tool", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolTimeoutException("tool", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
