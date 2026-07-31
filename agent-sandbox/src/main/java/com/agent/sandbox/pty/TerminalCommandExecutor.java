package com.agent.sandbox.pty;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** 异步终端命令执行协议。 */
@FunctionalInterface
public interface TerminalCommandExecutor {

    /**
     * 异步执行 Bash 命令。
     *
     * @param request     命令请求
     * @param logConsumer 实时日志接收器
     * @return 命令结果 future
     */
    CompletableFuture<CommandResult> execute(
            CommandRequest request,
            Consumer<TerminalLog> logConsumer);
}
