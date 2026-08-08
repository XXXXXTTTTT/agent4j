package com.agent.core.cli;

import com.agent.sandbox.pty.CommandRequest;
import java.util.Objects;
import java.util.regex.Pattern;

/** 已完成安全渲染的 CLI 命令计划。 */
public record CliCommandPlan(
        String name,
        CommandRequest request,
        CliRiskLevel riskLevel,
        String commandSha256) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    /** 校验计划字段。 */
    public CliCommandPlan {
        if (!CliValidation.isDefinitionName(name)) {
            throw new IllegalArgumentException("name 格式非法");
        }
        request = Objects.requireNonNull(request, "request 不能为空");
        riskLevel = Objects.requireNonNull(riskLevel, "riskLevel 不能为空");
        if (commandSha256 == null || !SHA_256.matcher(commandSha256).matches()) {
            throw new IllegalArgumentException("commandSha256 必须是 64 位小写十六进制");
        }
    }
}
