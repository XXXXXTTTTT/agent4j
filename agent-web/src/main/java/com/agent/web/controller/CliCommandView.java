package com.agent.web.controller;

import com.agent.core.cli.CliCommandDefinition;
import com.agent.core.cli.CliRiskLevel;
import com.agent.core.intent.RequiredCapability;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 受治理 CLI 命令目录的只读 HTTP 视图。 */
public record CliCommandView(
        String name,
        String executable,
        List<String> fixedArguments,
        CliRiskLevel riskLevel,
        List<RequiredCapability> requiredCapabilities,
        int maxArguments) {

    private static final int MAX_ARGUMENTS = 64;

    /** 从不可变命令定义创建只读视图。 */
    public static CliCommandView from(CliCommandDefinition definition) {
        Objects.requireNonNull(definition, "definition 不能为空");
        return new CliCommandView(
                definition.name(),
                definition.executable(),
                definition.fixedArguments(),
                definition.riskLevel(),
                definition.requiredCapabilities().stream()
                        .sorted(Comparator.comparingInt(Enum::ordinal))
                        .toList(),
                MAX_ARGUMENTS);
    }
}
