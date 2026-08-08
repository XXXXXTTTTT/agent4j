package com.agent.core.cli;

import com.agent.core.intent.RequiredCapability;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 目录中受治理的 CLI 命令定义。 */
public record CliCommandDefinition(
        String name,
        String executable,
        List<String> fixedArguments,
        CliRiskLevel riskLevel,
        Set<RequiredCapability> requiredCapabilities) {

    /** 校验定义并冻结集合字段。 */
    public CliCommandDefinition {
        CliValidation.validateDefinitionName(name);
        CliValidation.validateExecutable(name, executable);
        List<String> fixedArgumentSnapshot = new java.util.ArrayList<>(
                Objects.requireNonNull(fixedArguments, "fixedArguments 不能为空"));
        for (int index = 0; index < fixedArgumentSnapshot.size(); index++) {
            CliValidation.validateDefinitionArgument(name, fixedArgumentSnapshot.get(index), index);
        }
        fixedArguments = List.copyOf(fixedArgumentSnapshot);
        riskLevel = Objects.requireNonNull(riskLevel, "riskLevel 不能为空");
        requiredCapabilities = Set.copyOf(
                Objects.requireNonNull(requiredCapabilities, "requiredCapabilities 不能为空"));
    }
}
