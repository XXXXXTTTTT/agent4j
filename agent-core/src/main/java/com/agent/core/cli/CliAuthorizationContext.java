package com.agent.core.cli;

import com.agent.core.intent.RequiredCapability;
import java.util.Objects;
import java.util.Set;

/** CLI 命令的能力与审批上下文。 */
public record CliAuthorizationContext(
        Set<RequiredCapability> grantedCapabilities,
        boolean userApproved,
        boolean administratorApproved) {

    /** 冻结调用方传入的能力集合。 */
    public CliAuthorizationContext {
        grantedCapabilities = Set.copyOf(
                Objects.requireNonNull(grantedCapabilities, "grantedCapabilities 不能为空"));
    }
}
