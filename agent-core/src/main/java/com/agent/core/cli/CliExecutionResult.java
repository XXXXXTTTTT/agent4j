package com.agent.core.cli;

import com.agent.sandbox.pty.CommandResult;
import java.util.Objects;
import java.util.Optional;

/** CLI 授权与终端执行结果。 */
public record CliExecutionResult(
        CliAuthorization authorization,
        Optional<CommandResult> result) {

    /** 校验授权决策与终端结果的一致性。 */
    public CliExecutionResult {
        authorization = Objects.requireNonNull(authorization, "authorization 不能为空");
        result = Objects.requireNonNull(result, "result 不能为空");
        boolean allowed = authorization.decision() == CliAuthorizationDecision.ALLOWED;
        if (allowed != result.isPresent()) {
            throw new IllegalArgumentException("终端结果必须与授权决策一致");
        }
    }
}
