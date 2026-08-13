package com.agent.core.nodes;

import com.agent.core.cli.CliApprovalInterruptPolicy;
import com.agent.core.cli.CliAuthorization;
import com.agent.core.cli.CliAuthorizationContext;
import com.agent.core.cli.CliAuthorizationDecision;
import com.agent.core.cli.CliCommandCatalog;
import com.agent.core.cli.CliCommandIntent;
import com.agent.core.cli.WorkspaceTerminalTargetResolver;
import com.agent.core.engine.AgentState;
import com.agent.core.engine.Node;
import com.agent.core.engine.NodeExecutionContext;
import com.agent.core.harness.HarnessHookException;
import com.agent.core.intent.RequiredCapability;
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
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** 执行状态中 Bash 命令的运维节点。 */
public final class OpsNode implements Node {

    public static final String COMMAND_KEY = "ops.command";
    public static final String COMMAND_NAME_KEY = "ops.commandName";
    public static final String COMMAND_ARGUMENTS_KEY = "ops.commandArguments";
    public static final String COMMAND_TIMEOUT_SECONDS_KEY = "ops.commandTimeoutSeconds";
    public static final String COMMAND_SHA256_KEY = "ops.commandSha256";
    public static final String AUTHORIZATION_DECISION_KEY = "ops.authorizationDecision";
    public static final String AUTHORIZATION_REASON_KEY = "ops.authorizationReason";
    public static final String EXIT_CODE_KEY = "ops.exitCode";
    public static final String STDOUT_KEY = "ops.stdout";
    public static final String STDERR_KEY = "ops.stderr";
    public static final String TIMED_OUT_KEY = "ops.timedOut";
    public static final String ERROR_KEY = "ops.error";
    public static final String LOG_ERROR_KEY = "ops.logError";

    private final TerminalCommandExecutor executor;
    private final TerminalTarget target;
    private final WorkspaceTerminalTargetResolver targetResolver;
    private final CliCommandCatalog commandCatalog;
    private final Duration timeout;
    private final RunLogPublisher logPublisher;
    private final CliApprovalInterruptPolicy approvalPolicy;

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
        this.targetResolver = null;
        this.commandCatalog = null;
        this.timeout = Objects.requireNonNull(timeout, "timeout 不能为空");
        this.logPublisher = Objects.requireNonNull(logPublisher, "logPublisher 不能为空");
        this.approvalPolicy = null;
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须大于 0");
        }
    }

    /** 创建按每次运行的已授权工作区解析终端目标的代码执行节点。 */
    public OpsNode(
            TerminalCommandExecutor executor,
            WorkspaceTerminalTargetResolver targetResolver,
            Duration timeout) {
        this(executor, targetResolver, timeout, RunLogPublisher.noop());
    }

    /** 创建按每次运行的已授权工作区解析终端目标并发布日志的代码执行节点。 */
    public OpsNode(
            TerminalCommandExecutor executor,
            WorkspaceTerminalTargetResolver targetResolver,
            Duration timeout,
            RunLogPublisher logPublisher) {
        this.executor = Objects.requireNonNull(executor, "executor 不能为空");
        this.target = null;
        this.targetResolver = Objects.requireNonNull(targetResolver, "targetResolver 不能为空");
        this.commandCatalog = null;
        this.timeout = Objects.requireNonNull(timeout, "timeout 不能为空");
        this.logPublisher = Objects.requireNonNull(logPublisher, "logPublisher 不能为空");
        this.approvalPolicy = null;
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须大于 0");
        }
    }

    /** 创建按命令目录授权并按当前工作区解析执行目标的代码执行节点。 */
    public OpsNode(
            TerminalCommandExecutor executor,
            CliCommandCatalog commandCatalog,
            WorkspaceTerminalTargetResolver targetResolver,
            Duration timeout,
            RunLogPublisher logPublisher) {
        this.executor = Objects.requireNonNull(executor, "executor 不能为空");
        this.target = null;
        this.targetResolver = Objects.requireNonNull(targetResolver, "targetResolver 不能为空");
        this.commandCatalog = Objects.requireNonNull(commandCatalog, "commandCatalog 不能为空");
        this.timeout = Objects.requireNonNull(timeout, "timeout 不能为空");
        this.logPublisher = Objects.requireNonNull(logPublisher, "logPublisher 不能为空");
        this.approvalPolicy = null;
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须大于 0");
        }
    }

    /** 创建只执行目录授权计划的生产 Ops 节点。 */
    public OpsNode(
            TerminalCommandExecutor executor,
            CliApprovalInterruptPolicy approvalPolicy,
            RunLogPublisher logPublisher) {
        this.executor = Objects.requireNonNull(executor, "executor 不能为空");
        this.target = null;
        this.targetResolver = null;
        this.commandCatalog = null;
        this.timeout = null;
        this.logPublisher = Objects.requireNonNull(logPublisher, "logPublisher 不能为空");
        this.approvalPolicy = Objects.requireNonNull(
                approvalPolicy, "approvalPolicy 不能为空");
    }

    /**
     * 执行 Bash 命令，并返回包含完整结果的新状态。
     *
     * @param state 输入状态
     * @return 节点执行后的新状态
     */
    @Override
    public AgentState execute(AgentState state) {
        return executeCommand(
                state, ignored -> { }, new AtomicReference<>(), false);
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
        return executeCommand(
                state, logConsumer, logFailure, harness);
    }

    private AgentState executeCommand(
            AgentState state,
            Consumer<TerminalLog> logConsumer,
            AtomicReference<Throwable> logFailure,
            boolean harness) {
        Objects.requireNonNull(state, "state 不能为空");
        AgentState result;
        AgentState evidence = state;
        try {
            CommandRequest request;
            String command;
            if (approvalPolicy == null) {
                if (commandCatalog == null) {
                    command = requireCommand(state);
                    request = new CommandRequest(normalTarget(state), command, timeout);
                } else {
                    CliAuthorization authorization = authorizeCodeAgentCommand(state);
                    command = authorization.plan().request().bashCommand();
                    evidence = state
                            .withVariable(COMMAND_KEY, command)
                            .withVariable(COMMAND_SHA256_KEY,
                                    authorization.plan().commandSha256())
                            .withVariable(AUTHORIZATION_DECISION_KEY,
                                    authorization.decision().name())
                            .withVariable(AUTHORIZATION_REASON_KEY,
                                    authorization.reason());
                    if (authorization.decision() != CliAuthorizationDecision.ALLOWED) {
                        IllegalStateException failure = new IllegalStateException(
                                "代码 Agent 命令未获自动执行授权: " + authorization.reason());
                        return evidence
                                .withVariable(ERROR_KEY, stackTrace(failure))
                                .withTraceEntry("ops");
                    }
                    request = authorization.plan().request();
                }
            } else {
                CliAuthorization authorization = approvalPolicy.authorizeForExecution(
                        state, NodeExecutionContext.approvalBypassed());
                command = authorization.plan().request().bashCommand();
                evidence = state
                        .withVariable(COMMAND_KEY, command)
                        .withVariable(COMMAND_SHA256_KEY,
                                authorization.plan().commandSha256())
                        .withVariable(AUTHORIZATION_DECISION_KEY,
                                authorization.decision().name())
                        .withVariable(AUTHORIZATION_REASON_KEY,
                                authorization.reason());
                if (authorization.decision() != CliAuthorizationDecision.ALLOWED) {
                    IllegalStateException failure = new IllegalStateException(
                            "CLI 命令尚未获得执行授权: " + authorization.reason());
                    return evidence
                            .withVariable(ERROR_KEY, stackTrace(failure))
                            .withTraceEntry("ops");
                }
                request = authorization.plan().request();
            }
            NodeExecutionContext.progress("开始执行终端命令: " + command);
            CommandResult commandResult = executeTerminal(
                    request, command, logConsumer, harness);
            result = evidence
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
            result = evidence
                    .withVariable(ERROR_KEY, stackTrace(exception))
                    .withTraceEntry("ops");
        }
        Throwable publisherFailure = logFailure.get();
        if (publisherFailure != null) {
            return result.withVariable(LOG_ERROR_KEY, stackTrace(publisherFailure));
        }
        return result;
    }

    private TerminalTarget normalTarget(AgentState state) {
        if (targetResolver == null) {
            return target;
        }
        String workspace = state.variables().get(CoderNode.WORKSPACE_PATH_KEY);
        if (workspace == null || workspace.isBlank()) {
            throw new IllegalArgumentException(
                    "缺少状态变量: " + CoderNode.WORKSPACE_PATH_KEY);
        }
        return Objects.requireNonNull(
                targetResolver.resolve(java.nio.file.Path.of(workspace)),
                "targetResolver 返回值不能为空");
    }

    private CliAuthorization authorizeCodeAgentCommand(AgentState state) {
        String name = requireVariable(state, COMMAND_NAME_KEY);
        List<String> arguments = parseCommandArguments(
                requireVariable(state, COMMAND_ARGUMENTS_KEY));
        Path workspace = Path.of(requireVariable(state, CoderNode.WORKSPACE_PATH_KEY));
        TerminalTarget resolvedTarget = Objects.requireNonNull(
                targetResolver.resolve(workspace), "targetResolver 返回值不能为空");
        return commandCatalog.authorize(
                new CliCommandIntent(name, arguments, workspace, resolvedTarget, timeout),
                new CliAuthorizationContext(requiredCapabilities(state), false, false));
    }

    private List<String> parseCommandArguments(String serializedArguments) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(serializedArguments);
            if (root == null || !root.isArray()) {
                throw new IllegalArgumentException(COMMAND_ARGUMENTS_KEY + " 必须是 JSON 数组");
            }
            List<String> arguments = new ArrayList<>();
            for (int index = 0; index < root.size(); index++) {
                com.fasterxml.jackson.databind.JsonNode argument = root.get(index);
                if (!argument.isTextual()) {
                    throw new IllegalArgumentException(COMMAND_ARGUMENTS_KEY
                            + " 的第 " + index + " 项必须是字符串");
                }
                arguments.add(argument.textValue());
            }
            return List.copyOf(arguments);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException(COMMAND_ARGUMENTS_KEY + " 不是合法 JSON", exception);
        }
    }

    private Set<RequiredCapability> requiredCapabilities(AgentState state) {
        String serializedCapabilities = state.variables().get(PlannerNode.REQUIRED_CAPABILITIES_KEY);
        if (serializedCapabilities == null || serializedCapabilities.isBlank()) {
            return Set.of();
        }
        EnumSet<RequiredCapability> capabilities = EnumSet.noneOf(RequiredCapability.class);
        for (String capability : serializedCapabilities.split(",", -1)) {
            capabilities.add(RequiredCapability.valueOf(capability));
        }
        return Set.copyOf(capabilities);
    }

    private String requireVariable(AgentState state, String key) {
        String value = state.variables().get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少状态变量: " + key);
        }
        return value;
    }

    private CommandResult executeTerminal(
            CommandRequest request,
            String command,
            Consumer<TerminalLog> logConsumer,
            boolean harness) throws Exception {
        if (harness) {
            return NodeExecutionContext.callTool(
                    "terminal",
                    Map.of("command", command),
                    () -> executor.execute(
                                    request,
                                    logConsumer)
                            .get());
        }
        return executor.execute(request, logConsumer).get();
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
