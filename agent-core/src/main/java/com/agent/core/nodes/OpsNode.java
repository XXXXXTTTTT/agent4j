package com.agent.core.nodes;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.Node;
import com.agent.sandbox.pty.CommandRequest;
import com.agent.sandbox.pty.CommandResult;
import com.agent.sandbox.pty.TerminalCommandExecutor;
import com.agent.sandbox.pty.TerminalTarget;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.Objects;

/** 执行状态中 Bash 命令的运维节点。 */
public final class OpsNode implements Node {

    public static final String COMMAND_KEY = "ops.command";
    public static final String EXIT_CODE_KEY = "ops.exitCode";
    public static final String STDOUT_KEY = "ops.stdout";
    public static final String STDERR_KEY = "ops.stderr";
    public static final String TIMED_OUT_KEY = "ops.timedOut";
    public static final String ERROR_KEY = "ops.error";

    private final TerminalCommandExecutor executor;
    private final TerminalTarget target;
    private final Duration timeout;

    /**
     * 创建命令执行节点。
     *
     * @param executor 终端执行协议
     * @param target   执行目标
     * @param timeout  命令超时时间
     */
    public OpsNode(
            TerminalCommandExecutor executor,
            TerminalTarget target,
            Duration timeout) {
        this.executor = Objects.requireNonNull(executor, "executor 不能为空");
        this.target = Objects.requireNonNull(target, "target 不能为空");
        this.timeout = Objects.requireNonNull(timeout, "timeout 不能为空");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须大于 0");
        }
    }

    /**
     * 执行 Bash 命令，并返回包含完整结果的新状态。
     *
     * @param state 输入状态
     * @return 节点执行后的新状态
     */
    @Override
    public AgentState execute(AgentState state) {
        Objects.requireNonNull(state, "state 不能为空");
        try {
            String command = requireCommand(state);
            CommandResult result = executor.execute(
                            new CommandRequest(target, command, timeout),
                            ignored -> { })
                    .get();
            return state
                    .withVariable(EXIT_CODE_KEY, Integer.toString(result.exitCode()))
                    .withVariable(STDOUT_KEY, result.stdout())
                    .withVariable(STDERR_KEY, result.stderr())
                    .withVariable(TIMED_OUT_KEY, Boolean.toString(result.timedOut()))
                    .withTraceEntry("ops");
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return state
                    .withVariable(ERROR_KEY, stackTrace(exception))
                    .withTraceEntry("ops");
        }
    }

    private String requireCommand(AgentState state) {
        String command = state.variables().get(COMMAND_KEY);
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("缺少状态变量: " + COMMAND_KEY);
        }
        return command;
    }

    private String stackTrace(Exception exception) {
        StringWriter writer = new StringWriter();
        exception.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
