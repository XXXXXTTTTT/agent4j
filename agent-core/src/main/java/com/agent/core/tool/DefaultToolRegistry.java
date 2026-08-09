package com.agent.core.tool;

import com.agent.core.security.DefaultOutputRedactor;
import com.agent.core.security.DefaultToolParameterPolicy;
import com.agent.core.security.OutputRedactor;
import com.agent.core.security.SecurityDecision;
import com.agent.core.security.SecuritySeverity;
import com.agent.core.security.SecurityViolation;
import com.agent.core.security.SecurityViolationSink;
import com.agent.core.security.SecurityViolationType;
import com.agent.core.security.ToolParameterDecision;
import com.agent.core.security.ToolParameterPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** 基于虚拟线程的默认工具注册与执行实现。 */
public final class DefaultToolRegistry implements ToolRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultToolRegistry.class);

    private final ToolSchemaValidator schemaValidator;
    private final ToolAuthorizer authorizer;
    private final ToolAuditSink auditSink;
    private final ObjectMapper objectMapper;
    private final LongSupplier nanoTime;
    private final ToolParameterPolicy parameterPolicy;
    private final OutputRedactor outputRedactor;
    private final SecurityViolationSink securityViolationSink;
    private volatile Map<String, ToolDefinition> definitions = Map.of();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 使用默认确定性治理组件创建注册表。 */
    public DefaultToolRegistry() {
        this(new JacksonToolSchemaValidator(), new DefaultToolAuthorizer(), ToolAuditSink.noop(),
                new ObjectMapper(), System::nanoTime,
                new DefaultToolParameterPolicy(Map.of()),
                new DefaultOutputRedactor(), SecurityViolationSink.noop());
    }

    /** 注入全部策略与单调时钟，便于隔离测试和部署适配。 */
    public DefaultToolRegistry(
            ToolSchemaValidator schemaValidator,
            ToolAuthorizer authorizer,
            ToolAuditSink auditSink,
            ObjectMapper objectMapper,
            LongSupplier nanoTime) {
        this(schemaValidator, authorizer, auditSink, objectMapper, nanoTime,
                new DefaultToolParameterPolicy(Map.of()),
                new DefaultOutputRedactor(), SecurityViolationSink.noop());
    }

    /** 注入参数策略、输出脱敏器和安全违规 Sink。 */
    public DefaultToolRegistry(
            ToolSchemaValidator schemaValidator,
            ToolAuthorizer authorizer,
            ToolAuditSink auditSink,
            ObjectMapper objectMapper,
            LongSupplier nanoTime,
            ToolParameterPolicy parameterPolicy,
            OutputRedactor outputRedactor,
            SecurityViolationSink securityViolationSink) {
        this.schemaValidator = Objects.requireNonNull(schemaValidator, "schemaValidator 不能为空");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer 不能为空");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime 不能为空");
        this.parameterPolicy = Objects.requireNonNull(parameterPolicy, "parameterPolicy 不能为空");
        this.outputRedactor = Objects.requireNonNull(outputRedactor, "outputRedactor 不能为空");
        this.securityViolationSink = Objects.requireNonNull(
                securityViolationSink, "securityViolationSink 不能为空");
    }

    @Override
    public void register(ToolDefinition definition) {
        Objects.requireNonNull(definition, "definition 不能为空");
        registerAll(List.of(definition));
    }

    @Override
    public synchronized void registerAll(List<ToolDefinition> inputDefinitions) {
        ensureOpen();
        Objects.requireNonNull(inputDefinitions, "definitions 不能为空");
        List<ToolDefinition> batch = List.copyOf(inputDefinitions);
        java.util.HashSet<String> batchNames = new java.util.HashSet<>();
        for (ToolDefinition definition : batch) {
            if (!batchNames.add(definition.name())) {
                throw new ToolRegistrationException(
                        definition.name(), "批量工具名称重复: " + definition.name(), null);
            }
            if (definitions.containsKey(definition.name())) {
                throw new ToolRegistrationException(
                        definition.name(), "工具名称已注册: " + definition.name(), null);
            }
            try {
                schemaValidator.validateSchema(definition.inputSchema());
            } catch (Throwable exception) {
                if (exception instanceof ToolRegistrationException registrationException) {
                    throw registrationException;
                }
                throw new ToolRegistrationException(definition.name(), "工具 Schema 注册校验失败", exception);
            }
        }
        HashMap<String, ToolDefinition> next = new HashMap<>(definitions);
        for (ToolDefinition definition : batch) {
            next.put(definition.name(), definition);
        }
        definitions = Map.copyOf(next);
    }

    @Override
    public Optional<ToolDefinition> find(String name) {
        ensureOpen();
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(definitions.get(name));
    }

    @Override
    public List<ToolDefinition> list() {
        ensureOpen();
        List<ToolDefinition> result = new ArrayList<>(definitions.values());
        result.sort(Comparator.comparing(ToolDefinition::name));
        return List.copyOf(result);
    }

    @Override
    public ToolResult execute(ToolCall call, ToolInvocationContext context) {
        ensureOpen();
        Objects.requireNonNull(call, "call 不能为空");
        Objects.requireNonNull(context, "context 不能为空");
        long started = nanoTime.getAsLong();
        String argumentsSha256 = sha256(call.arguments());
        ToolDefinition definition = definitions.get(call.name());
        if (definition == null) {
            ToolNotFoundException exception = new ToolNotFoundException(call.name());
            return finish(call, context, Optional.empty(), ToolResultStatus.FAILED,
                    NullNode.getInstance(), exception, started, false, argumentsSha256);
        }

        try {
            schemaValidator.validateArguments(definition.inputSchema(), call.arguments());
        } catch (Throwable exception) {
            return finish(call, context, Optional.of(definition.riskLevel()), ToolResultStatus.DENIED,
                    NullNode.getInstance(), exception, started, false, argumentsSha256);
        }

        ToolParameterDecision parameterDecision;
        try {
            parameterDecision = Objects.requireNonNull(
                    parameterPolicy.inspect(definition, call, context),
                    "参数策略不得返回 null");
        } catch (Throwable exception) {
            recordViolation(context, Optional.of(definition.name()), SecurityViolationType.TOOL_PARAMETER,
                    SecuritySeverity.HIGH, "security.tool-parameter-policy-failure",
                    "工具参数策略执行失败");
            return finish(call, context, Optional.of(definition.riskLevel()), ToolResultStatus.DENIED,
                    NullNode.getInstance(), exception, started, false, argumentsSha256);
        }
        if (parameterDecision.decision() != SecurityDecision.ALLOW) {
            recordViolation(context, Optional.of(definition.name()), SecurityViolationType.TOOL_PARAMETER,
                    SecuritySeverity.HIGH, parameterDecision.ruleId(), parameterDecision.summary());
            ToolException exception = new ToolAuthorizationException(
                    definition.name(), parameterDecision.summary());
            return finish(call, context, Optional.of(definition.riskLevel()), ToolResultStatus.DENIED,
                    NullNode.getInstance(), exception, started, false, argumentsSha256);
        }

        ToolAuthorization authorization;
        try {
            authorization = Objects.requireNonNull(
                    authorizer.authorize(definition, call, context), "授权器不得返回 null");
        } catch (Throwable exception) {
            return finish(call, context, Optional.of(definition.riskLevel()), ToolResultStatus.DENIED,
                    NullNode.getInstance(), exception, started, false, argumentsSha256);
        }
        if (authorization.decision() != ToolAuthorizationDecision.ALLOWED) {
            SecuritySeverity severity = authorization.decision() == ToolAuthorizationDecision.DENIED
                    ? SecuritySeverity.HIGH : SecuritySeverity.MEDIUM;
            String ruleId = authorization.decision() == ToolAuthorizationDecision.DENIED
                    ? "security.tool-authorization-denied" : "security.tool-approval-required";
            String summary = authorization.decision() == ToolAuthorizationDecision.DENIED
                    ? "工具权限校验拒绝调用" : "工具调用需要人工审批";
            recordViolation(context, Optional.of(definition.name()), SecurityViolationType.AUTHORIZATION,
                    severity, ruleId, summary);
            ToolException exception = authorization.decision() == ToolAuthorizationDecision.DENIED
                    ? new ToolAuthorizationException(definition.name(), authorization.reason())
                    : new ToolApprovalRequiredException(definition.name(), authorization.reason());
            ToolResultStatus status = authorization.decision() == ToolAuthorizationDecision.DENIED
                    ? ToolResultStatus.DENIED : ToolResultStatus.APPROVAL_REQUIRED;
            return finish(call, context, Optional.of(definition.riskLevel()), status,
                    NullNode.getInstance(), exception, started, false, argumentsSha256);
        }

        Future<JsonNode> future = executor.submit(() -> definition.handler().execute(call, context));
        try {
            JsonNode output = future.get(definition.timeout().toNanos(), TimeUnit.NANOSECONDS);
            if (output == null || (!output.isObject() && !output.isArray())) {
                throw new IllegalArgumentException("工具 handler 必须返回 JSON object 或 array");
            }
            JsonNode redactedOutput;
            try {
                redactedOutput = outputRedactor.redact(definition.name(), output);
            } catch (Throwable exception) {
                recordViolation(context, Optional.of(definition.name()),
                        SecurityViolationType.OUTPUT_REDACTION, SecuritySeverity.CRITICAL,
                        "security.output-redaction-failure", "工具输出脱敏失败");
                return finish(call, context, Optional.of(definition.riskLevel()), ToolResultStatus.FAILED,
                        NullNode.getInstance(), exception, started, false, argumentsSha256);
            }
            return finish(call, context, Optional.of(definition.riskLevel()), ToolResultStatus.SUCCEEDED,
                    redactedOutput, null, started, false, argumentsSha256);
        } catch (TimeoutException exception) {
            boolean cancellationRequested = future.cancel(true);
            ToolTimeoutException timeout = new ToolTimeoutException(definition.name(), definition.timeout());
            return finish(call, context, Optional.of(definition.riskLevel()), ToolResultStatus.TIMED_OUT,
                    NullNode.getInstance(), timeout, started, cancellationRequested, argumentsSha256);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return finish(call, context, Optional.of(definition.riskLevel()), ToolResultStatus.FAILED,
                    NullNode.getInstance(), exception, started, future.cancel(true), argumentsSha256);
        } catch (CancellationException exception) {
            return finish(call, context, Optional.of(definition.riskLevel()), ToolResultStatus.FAILED,
                    NullNode.getInstance(), exception, started, false, argumentsSha256);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            return finish(call, context, Optional.of(definition.riskLevel()), ToolResultStatus.FAILED,
                    NullNode.getInstance(), cause, started, false, argumentsSha256);
        } catch (Throwable exception) {
            return finish(call, context, Optional.of(definition.riskLevel()), ToolResultStatus.FAILED,
                    NullNode.getInstance(), exception, started, false, argumentsSha256);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow();
        }
    }

    private ToolResult finish(
            ToolCall call,
            ToolInvocationContext context,
            Optional<ToolRiskLevel> riskLevel,
            ToolResultStatus status,
            JsonNode output,
            Throwable failure,
            long started,
            boolean cancellationRequested,
            String argumentsSha256) {
        Throwable error = failure;
        long durationMs = durationMillis(started);
        String errorStack = failure == null ? "" : stackTrace(failure);
        ToolResult result = new ToolResult(call.callId(), call.name(), status,
                status == ToolResultStatus.SUCCEEDED ? output : NullNode.getInstance(), errorStack, durationMs);
        ToolAuditEvent event = new ToolAuditEvent(context.runId(), context.nodeName(), context.userId(),
                call.callId(), call.name(), riskLevel, status, durationMs, argumentsSha256,
                failure == null ? "" : errorType(failure), cancellationRequested);
        try {
            auditSink.record(event);
        } catch (Throwable auditFailure) {
            if (error != null) {
                error.addSuppressed(auditFailure);
                result = new ToolResult(call.callId(), call.name(), status, NullNode.getInstance(),
                        stackTrace(error), durationMs);
            } else {
                LOGGER.error("工具成功后的审计记录失败: toolName={}, callId={}", call.name(), call.callId(), auditFailure);
            }
        }
        return result;
    }

    private long durationMillis(long started) {
        long elapsed = nanoTime.getAsLong() - started;
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(elapsed));
    }

    private String sha256(JsonNode arguments) {
        try {
            JsonNode canonical = canonicalize(arguments);
            byte[] bytes = objectMapper.writeValueAsBytes(canonical);
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("工具参数哈希失败", exception);
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = JsonNodeFactory.instance.objectNode();
            var entries = node.fields();
            while (entries.hasNext()) {
                var entry = entries.next();
                object.set(entry.getKey(), canonicalize(entry.getValue()));
            }
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            object.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach(sorted::set);
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            node.elements().forEachRemaining(value -> array.add(canonicalize(value)));
            return array;
        }
        return node.deepCopy();
    }

    private static String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private static String errorType(Throwable throwable) {
        String simpleName = throwable.getClass().getSimpleName();
        return simpleName.isBlank() ? throwable.getClass().getName() : simpleName;
    }

    private void recordViolation(
            ToolInvocationContext context,
            Optional<String> toolName,
            SecurityViolationType type,
            SecuritySeverity severity,
            String ruleId,
            String summary) {
        SecurityViolation violation = new SecurityViolation(
                UUID.randomUUID(), context.runId(), context.userId(), context.nodeName(),
                toolName, type, severity, ruleId, summary, java.time.Instant.now());
        try {
            securityViolationSink.record(violation);
        } catch (Throwable failure) {
            LOGGER.error("安全违规持久化失败: type={}, ruleId={}", type, ruleId, failure);
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("ToolRegistry 已关闭");
        }
    }
}
