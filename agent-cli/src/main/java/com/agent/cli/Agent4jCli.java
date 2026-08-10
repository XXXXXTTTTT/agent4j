package com.agent.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Agent4J 持久化工作区与会话的交互式命令行客户端。 */
public final class Agent4jCli {

    private static final String SERVER_WORKSPACE_PATH = "/agent-workspace";
    private static final String DEFAULT_WORKSPACE_NAME = "Agent4J";
    private static final String DEFAULT_REPOSITORY_ID = "local";
    private static final Duration TURN_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration SSE_JOIN_TIMEOUT = Duration.ofSeconds(5);

    private final Agent4jClient client;
    private final BufferedReader input;
    private final Consumer<String> output;
    private final Runnable pollWait;
    private final ObjectMapper objectMapper;
    private final Object outputLock = new Object();

    private Agent4jClient.Workspace workspace;
    private Agent4jClient.Conversation conversation;

    /** 创建使用 250 毫秒轮询间隔的交互客户端。 */
    public Agent4jCli(Agent4jClient client, Reader input, Consumer<String> output) {
        this(client, input, output, Agent4jCli::defaultPollWait);
    }

    Agent4jCli(
            Agent4jClient client,
            Reader input,
            Consumer<String> output,
            Runnable pollWait) {
        this.client = Objects.requireNonNull(client, "client 不能为空");
        this.input = new BufferedReader(Objects.requireNonNull(input, "input 不能为空"));
        this.output = Objects.requireNonNull(output, "output 不能为空");
        this.pollWait = Objects.requireNonNull(pollWait, "pollWait 不能为空");
        this.objectMapper = new ObjectMapper();
    }

    /** 连接服务端工作区并运行交互循环，正常退出返回 0。 */
    public int run(Path localWorkspace) {
        validateLocalWorkspace(localWorkspace);
        Agent4jClient.Actor actor = client.identity();
        workspace = resolveWorkspace();
        conversation = restoreConversation(workspace.workspaceId());
        emit("已连接: " + actor.displayName() + " (" + actor.userId() + ")");
        emit("工作区: " + workspace.displayName() + " [" + workspace.workspacePath()
                + "] " + workspace.permission());
        emit("当前会话: " + conversation.conversationId() + " " + conversation.title());

        try {
            String line;
            while ((line = input.readLine()) != null) {
                String command = line.trim();
                if (command.isEmpty()) {
                    continue;
                }
                if ("/exit".equals(command)) {
                    return 0;
                }
                execute(command);
            }
            return 0;
        } catch (IOException exception) {
            throw new IllegalStateException("读取 CLI 输入失败", exception);
        }
    }

    private void execute(String command) {
        try {
            if ("/new".equals(command)) {
                conversation = client.createConversation(workspace.workspaceId());
                emit("已创建会话: " + conversation.conversationId());
            } else if ("/sessions".equals(command)) {
                printSessions();
            } else if (command.startsWith("/use ")) {
                useConversation(command.substring("/use ".length()));
            } else if ("/status".equals(command)) {
                printStatus();
            } else if (command.startsWith("/")) {
                emit("未知命令: " + command);
            } else {
                submit(command);
            }
        } catch (RuntimeException exception) {
            emit("错误: " + exception.getMessage());
        }
    }

    private Agent4jClient.Workspace resolveWorkspace() {
        return client.listWorkspaces().stream()
                .filter(item -> SERVER_WORKSPACE_PATH.equals(item.workspacePath()))
                .findFirst()
                .orElseGet(() -> client.createWorkspace(
                        DEFAULT_WORKSPACE_NAME,
                        SERVER_WORKSPACE_PATH,
                        DEFAULT_REPOSITORY_ID));
    }

    private Agent4jClient.Conversation restoreConversation(UUID workspaceId) {
        List<Agent4jClient.Conversation> conversations = client.listConversations(workspaceId);
        return conversations.isEmpty()
                ? client.createConversation(workspaceId)
                : conversations.getFirst();
    }

    private void printSessions() {
        List<Agent4jClient.Conversation> conversations =
                client.listConversations(workspace.workspaceId());
        if (conversations.isEmpty()) {
            emit("当前工作区没有会话");
            return;
        }
        for (Agent4jClient.Conversation item : conversations) {
            String marker = item.conversationId().equals(conversation.conversationId())
                    ? "*"
                    : " ";
            emit(marker + " " + item.conversationId() + " " + item.status()
                    + " " + item.title());
        }
    }

    private void useConversation(String id) {
        UUID conversationId = UUID.fromString(id.trim());
        conversation = client.listConversations(workspace.workspaceId()).stream()
                .filter(item -> conversationId.equals(item.conversationId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "当前工作区不存在会话: " + conversationId));
        emit("已切换会话: " + conversation.conversationId());
    }

    private void printStatus() {
        List<Agent4jClient.Turn> turns = client.listTurns(conversation.conversationId());
        if (turns.isEmpty()) {
            emit("会话状态: EMPTY");
            return;
        }
        Agent4jClient.Turn turn = turns.getLast();
        if (turn.runId() == null) {
            emit("Turn " + turn.turnId() + " status=" + turn.status());
            return;
        }
        Agent4jClient.Run run = client.getRun(turn.runId());
        emit("Run " + run.runId() + " status=" + run.status()
                + " node=" + nullText(run.nextNode()));
    }

    private void submit(String content) {
        Agent4jClient.Turn submitted = client.submitTurn(
                conversation.conversationId(), content, "");
        UUID runId = Objects.requireNonNull(submitted.runId(), "提交轮次未返回 runId");
        emit("Run 已启动: " + runId);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> trace = executor.submit(() -> client.followTrace(runId, this::printTrace));
            Future<?> logs = executor.submit(() -> client.followLogs(runId, this::printLog));
            Agent4jClient.Turn completed = awaitTerminalTurn(submitted);
            awaitSse(trace, "Trace");
            awaitSse(logs, "终端日志");
            if ("COMPLETED".equals(completed.status())) {
                emit(nullText(completed.assistantContent()));
            } else {
                emit("执行失败: " + nullText(completed.error()));
            }
        }
    }

    private Agent4jClient.Turn awaitTerminalTurn(Agent4jClient.Turn submitted) {
        long deadline = System.nanoTime() + TURN_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            Agent4jClient.Turn current = client.listTurns(conversation.conversationId()).stream()
                    .filter(turn -> submitted.turnId().equals(turn.turnId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "服务端未返回已提交轮次: " + submitted.turnId()));
            if ("COMPLETED".equals(current.status()) || "FAILED".equals(current.status())) {
                return current;
            }
            pollWait.run();
        }
        throw new IllegalStateException("等待轮次完成超时: " + submitted.turnId());
    }

    private void awaitSse(Future<?> future, String streamName) {
        try {
            future.get(SSE_JOIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            emit(streamName + " 连接关闭超时");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 " + streamName + " 被中断", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            emit(streamName + " 读取失败: " + (cause == null ? exception : cause).getMessage());
        }
    }

    private void printTrace(SseEventReader.SseEvent frame) {
        JsonNode data = readJson(frame.data(), "Trace");
        if (!"EVENT".equals(data.path("kind").asText())) {
            return;
        }
        JsonNode event = data.path("event");
        String type = event.path("type").asText();
        String nodeName = event.path("nodeName").asText();
        switch (type) {
            case "NODE_STARTED" -> emit("[trace] " + nodeName + " 开始");
            case "NODE_PROGRESS" -> emit("[" + nodeName + "] "
                    + event.path("summary").asText());
            case "NODE_COMPLETED" -> emit("[trace] " + nodeName + " -> "
                    + event.path("nextNode").asText());
            case "FAILED" -> emit("[trace] 失败: " + event.path("error").asText());
            case "INTERRUPTED", "APPROVED", "REJECTED", "COMPLETED" ->
                    emit("[trace] " + type);
            default -> throw new IllegalStateException("未知 Trace 事件类型: " + type);
        }
    }

    private void printLog(SseEventReader.SseEvent frame) {
        JsonNode data = readJson(frame.data(), "终端日志");
        String kind = data.path("kind").asText();
        if ("LOG".equals(kind)) {
            emit(data.path("event").path("text").asText());
        } else if ("SNAPSHOT".equals(kind)) {
            JsonNode terminal = data.path("terminal");
            emitIfPresent(terminal.path("stdout").asText());
            emitIfPresent(terminal.path("stderr").asText());
        } else {
            throw new IllegalStateException("未知终端帧类别: " + kind);
        }
    }

    private JsonNode readJson(String value, String streamName) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(streamName + " JSON 解析失败", exception);
        }
    }

    private void emitIfPresent(String value) {
        if (value != null && !value.isEmpty()) {
            emit(value);
        }
    }

    private void emit(String value) {
        String safe = sanitize(nullText(value));
        synchronized (outputLock) {
            output.accept(safe);
        }
    }

    static String sanitize(String value) {
        return new OutputRedactor(List.of()).redact(value);
    }

    private static void validateLocalWorkspace(Path workspace) {
        Objects.requireNonNull(workspace, "workspace 不能为空");
        try {
            if (!Files.isDirectory(workspace.toRealPath())) {
                throw new IllegalArgumentException("workspace 必须是现有目录: " + workspace);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("workspace 不存在: " + workspace, exception);
        }
    }

    private static void defaultPollWait() {
        try {
            Thread.sleep(Duration.ofMillis(250));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待会话轮次被中断", exception);
        }
    }

    private static String nullText(String value) {
        return value == null ? "" : value;
    }

    /** CLI 输出的固定凭据格式脱敏器。 */
    private static final class OutputRedactor {

        private static final String REDACTED = "[REDACTED]";
        private static final String SENSITIVE_KEY =
                "[A-Za-z0-9_-]*(?:api[_-]?key|authorization|password|secret|token)";
        private static final Pattern BEARER = Pattern.compile(
                "(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");
        private static final Pattern OPEN_AI_KEY = Pattern.compile(
                "(?<![A-Za-z0-9])sk-[A-Za-z0-9_-]{4,}");
        private static final Pattern QUOTED_SENSITIVE_ASSIGNMENT = Pattern.compile(
                "(?i)((?<![A-Za-z0-9])" + SENSITIVE_KEY
                        + "[\\\"']?\\s*[:=]\\s*[\\\"'])(.*?)([\\\"'])");
        private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
                "(?i)((?<![A-Za-z0-9])" + SENSITIVE_KEY
                        + "[\\\"']?\\s*[:=]\\s*)([^\\s,;，。}\\\"']+)");
        private static final Pattern ENV_FILE = Pattern.compile("(?i)(?<![A-Za-z0-9])\\.env\\b");

        private final List<String> configuredSecrets;

        private OutputRedactor(Collection<String> configuredSecrets) {
            this.configuredSecrets = configuredSecrets.stream()
                    .filter(Objects::nonNull)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .sorted(Comparator.comparingInt(String::length).reversed())
                    .toList();
        }

        private String redact(String value) {
            String redacted = value;
            for (String secret : configuredSecrets) {
                redacted = redacted.replace(secret, REDACTED);
            }
            redacted = BEARER.matcher(redacted).replaceAll("Bearer " + REDACTED);
            redacted = OPEN_AI_KEY.matcher(redacted).replaceAll(REDACTED);
            redacted = QUOTED_SENSITIVE_ASSIGNMENT.matcher(redacted)
                    .replaceAll("$1" + REDACTED + "$3");
            redacted = SENSITIVE_ASSIGNMENT.matcher(redacted)
                    .replaceAll("$1" + REDACTED);
            return ENV_FILE.matcher(redacted).replaceAll(REDACTED);
        }
    }
}
