package com.agent.core.cli;

import com.agent.sandbox.pty.CommandResult;
import com.agent.sandbox.pty.TerminalCommandExecutor;
import com.agent.sandbox.pty.TerminalLog;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** 把 CLI 授权结果适配到现有终端执行协议。 */
public final class GovernedCliCommandExecutor {

    private final CliCommandCatalog catalog;
    private final TerminalCommandExecutor terminalExecutor;

    /** 注入只读命令目录和终端执行器。 */
    public GovernedCliCommandExecutor(
            CliCommandCatalog catalog,
            TerminalCommandExecutor terminalExecutor) {
        this.catalog = Objects.requireNonNull(catalog, "catalog 不能为空");
        this.terminalExecutor = Objects.requireNonNull(terminalExecutor, "terminalExecutor 不能为空");
    }

    /** 授权后异步执行命令，拒绝决策不创建终端任务。 */
    public CompletableFuture<CliExecutionResult> execute(
            CliCommandIntent intent,
            CliAuthorizationContext context,
            Consumer<TerminalLog> logConsumer) {
        Objects.requireNonNull(logConsumer, "logConsumer 不能为空");
        CliAuthorization authorization = catalog.authorize(intent, context);
        if (authorization.decision() != CliAuthorizationDecision.ALLOWED) {
            return CompletableFuture.completedFuture(
                    new CliExecutionResult(authorization, Optional.empty()));
        }

        final CompletableFuture<CommandResult> terminalResult;
        try {
            terminalResult = Objects.requireNonNull(
                    terminalExecutor.execute(authorization.plan().request(), logConsumer),
                    "terminalExecutor 返回的 future 不能为空");
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        return terminalResult.thenApply(result -> new CliExecutionResult(
                authorization,
                Optional.of(Objects.requireNonNull(result, "terminal result 不能为空"))));
    }
}
