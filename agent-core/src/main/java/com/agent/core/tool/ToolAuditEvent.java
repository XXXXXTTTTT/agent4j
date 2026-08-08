package com.agent.core.tool;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** 不包含参数正文的工具调用审计事件。 */
public record ToolAuditEvent(
        UUID runId,
        String nodeName,
        String userId,
        String callId,
        String toolName,
        Optional<ToolRiskLevel> riskLevel,
        ToolResultStatus status,
        long durationMs,
        String argumentsSha256,
        String errorType,
        boolean cancellationRequested) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /** 校验可审计字段与状态关联的不变量。 */
    public ToolAuditEvent {
        Objects.requireNonNull(runId, "runId 不能为空");
        requireText(nodeName, "nodeName");
        requireText(userId, "userId");
        requireText(callId, "callId");
        ToolDefinition.requireName(toolName);
        riskLevel = Objects.requireNonNull(riskLevel, "riskLevel 不能为空");
        Objects.requireNonNull(status, "status 不能为空");
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs 不能为负数");
        }
        if (argumentsSha256 == null || !SHA256.matcher(argumentsSha256).matches()) {
            throw new IllegalArgumentException("argumentsSha256 必须是 64 位小写十六进制");
        }
        Objects.requireNonNull(errorType, "errorType 不能为空");
        if (status == ToolResultStatus.SUCCEEDED && !errorType.isEmpty()) {
            throw new IllegalArgumentException("SUCCEEDED 的 errorType 必须为空");
        }
        if (status != ToolResultStatus.SUCCEEDED && errorType.isBlank()) {
            throw new IllegalArgumentException("失败结果的 errorType 不能为空");
        }
        boolean unknownToolFailure = status == ToolResultStatus.FAILED
                && errorType.equals(ToolNotFoundException.class.getSimpleName());
        if (riskLevel.isEmpty() && !unknownToolFailure) {
            throw new IllegalArgumentException("只有未知工具失败允许缺少 riskLevel");
        }
        if (riskLevel.isPresent() && unknownToolFailure) {
            throw new IllegalArgumentException("未知工具失败不得伪造 riskLevel");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}
