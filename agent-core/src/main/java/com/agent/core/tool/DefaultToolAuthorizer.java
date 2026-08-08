package com.agent.core.tool;

import com.agent.core.intent.RequiredCapability;

import java.util.EnumSet;
import java.util.Objects;
import java.util.stream.Collectors;

/** 基于能力集合和风险等级的默认授权器。 */
public final class DefaultToolAuthorizer implements ToolAuthorizer {

    @Override
    public ToolAuthorization authorize(
            ToolDefinition definition,
            ToolCall call,
            ToolInvocationContext context) {
        Objects.requireNonNull(definition, "definition 不能为空");
        Objects.requireNonNull(call, "call 不能为空");
        Objects.requireNonNull(context, "context 不能为空");

        EnumSet<RequiredCapability> missing = definition.requiredCapabilities().isEmpty()
                ? EnumSet.noneOf(RequiredCapability.class)
                : EnumSet.copyOf(definition.requiredCapabilities());
        missing.removeAll(context.grantedCapabilities());
        if (!missing.isEmpty()) {
            String reason = "缺少能力: " + missing.stream()
                    .map(Enum::name)
                    .collect(Collectors.joining(", "));
            return new ToolAuthorization(ToolAuthorizationDecision.DENIED, reason);
        }
        if (definition.riskLevel() == ToolRiskLevel.HIGH && !context.approvalGranted()) {
            return new ToolAuthorization(ToolAuthorizationDecision.APPROVAL_REQUIRED,
                    "HIGH 风险工具需要人工审批");
        }
        return new ToolAuthorization(ToolAuthorizationDecision.ALLOWED, "");
    }
}
