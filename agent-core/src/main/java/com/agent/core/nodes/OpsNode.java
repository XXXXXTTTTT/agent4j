package com.agent.core.nodes;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.Node;
import com.agent.core.engine.NodeExecutionContext;
import com.agent.core.harness.HarnessHookException;
import com.agent.core.trace.RunLogEvent;
import com.agent.core.trace.RunLogPublisher;
import com.agent.core.trace.RunLogStream;
import com.agent.sandbox.pty.CommandRequest;
import com.agent.sandbox.pty.CommandResult;
import com.agent.sandbox.pty.TerminalCommandExecutor;
import com.agent.sandbox.pty.TerminalLog;
import com.agent.sandbox.pty.TerminalTarget;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** 执行状态中 Bash 命令的运维节点。 */
public final class OpsNode implements Node {

    public static final String COMMAND_KEY = "ops.command";
    public static final String COMMAND_NAME_KEY = "ops.commandName";
    public static final String COMMAND_ARGUMENTS_KEY = "ops.commandArguments";
    public static final String EXIT_CODE_KEY = "ops.exitCode";
    public static final String STDOUT_KEY = "ops.stdout";
    public static final String STDERR_KEY = "ops.stderr";
    public static final String TIMED_OUT_KEY = "ops.timedOut";
    public static final String ERROR_KEY = "ops.error";
    public static final String LOG_ERROR_KEY = "ops.logError";

    private final TerminalCommandExecutor executor;
    private final TerminalTarget target;
    private final Duration timeout;
    private final RunLogPublisher logPublisher;

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
        this(executor, target, timeout, RunLogPublisher.noop());
    }

    /**
     * 创建支持实时 Run 日志发布的命令执行节点。
     *
     * @param executor 终端执行协议
     * @param target 执行目标
     * @param timeout 命令超时时间
     * @param logPublisher Run 日志发布端口
     */
    public OpsNode(
            TerminalCommandExecutor executor,
            TerminalTarget target,
            Duration timeout,
            RunLogPublisher logPublisher) {
        this.executor = Objects.requireNonNull(executor, "executor 不能为空");
        this.target = Objects.requireNonNull(target, "target 不能为空");
        this.timeout = Objects.requireNonNull(timeout, "timeout 不能为空");
        this.logPublisher = Objects.requireNonNull(logPublisher, "logPublisher 不能为空");
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
        return executeCommand(state, ignored -> { }, new AtomicReference<>(), false);
    }

    /** 在 Run 上下文中执行命令并发布原始终端片段。 */
    @Override
    public AgentState execute(NodeExecutionContext context, AgentState state) {
        Objects.requireNonNull(context, "context 不能为空");
        AtomicLong sequence = new AtomicLong();
        AtomicReference<Throwable> logFailure = new AtomicReference<>();
        Consumer<TerminalLog> logConsumer = log -> publishLog(
                context, log, sequence.getAndIncrement(), logFailure);
        boolean harness = NodeExecutionContext.current()
                .filter(context::equals)
                .isPresent();
        return executeCommand(state, logConsumer, logFailure, harness);
    }

    private AgentState executeCommand(
            AgentState state,
            Consumer<TerminalLog> logConsumer,
            AtomicReference<Throwable> logFailure,
            boolean harness) {
        Objects.requireNonNull(state, "state 不能为空");
        AgentState result;
        try {
            String command = requireCommand(state);
            NodeExecutionContext.progress("开始执行终端命令: " + command);
            CommandResult commandResult = executeTerminal(command, logConsumer, harness);
            result = state
                    .withVariable(EXIT_CODE_KEY, Integer.toString(commandResult.exitCode()))
                    .withVariable(STDOUT_KEY, commandResult.stdout())
                    .withVariable(STDERR_KEY, commandResult.stderr())
                    .withVariable(TIMED_OUT_KEY, Boolean.toString(commandResult.timedOut()))
                    .withTraceEntry("ops");
            NodeExecutionContext.progress("终端命令已结束，退出码 " + commandResult.exitCode());
        } catch (HarnessHookException exception) {
            throw exception;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            result = state
                    .withVariable(ERROR_KEY, stackTrace(exception))
                    .withTraceEntry("ops");
        }
        Throwable publisherFailure = logFailure.get();
        if (publisherFailure != null) {
            return result.withVariable(LOG_ERROR_KEY, stackTrace(publisherFailure));
        }
        return result;
    }

    private CommandResult executeTerminal(
            String command,
            Consumer<TerminalLog> logConsumer,
            boolean harness) throws Exception {
        if (harness) {
            return NodeExecutionContext.callTool(
                    "terminal",
                    Map.of("command", command),
                    () -> executor.execute(
                                    new CommandRequest(target, command, timeout),
                                    logConsumer)
                            .get());
        }
        return executor.execute(new CommandRequest(target, command, timeout), logConsumer).get();
    }

    private void publishLog(
            NodeExecutionContext context,
            TerminalLog log,
            long sequence,
            AtomicReference<Throwable> logFailure) {
        Objects.requireNonNull(log, "log 不能为空");
        RunLogStream stream = switch (log.stream()) {
            case STDOUT -> RunLogStream.STDOUT;
            case STDERR -> RunLogStream.STDERR;
            case PTY -> RunLogStream.PTY;
        };
        try {
            logPublisher.publish(new RunLogEvent(
                    UUID.randomUUID(),
                    context.runId(),
                    context.nodeName(),
                    sequence,
                    stream,
                    log.text(),
                    Instant.now()));
        } catch (RuntimeException exception) {
            logFailure.compareAndSet(null, exception);
        }
    }

    private String requireCommand(AgentState state) {
        String command = state.variables().get(COMMAND_KEY);
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("缺少状态变量: " + COMMAND_KEY);
        }
        return command;
    }

    private String stackTrace(Throwable exception) {
        StringWriter writer = new StringWriter();
        exception.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
