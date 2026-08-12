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
import java.time.Duration;
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
    private static final String BUILTIN_OWNER_ID = "builtin";

    private final Object lifecycleMonitor = new Object();
    private volatile RegistrySnapshot snapshot = RegistrySnapshot.empty();
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
        registerOwned(BUILTIN_OWNER_ID, List.of(definition));
    }

    @Override
    public void registerAll(List<ToolDefinition> inputDefinitions) {
        registerOwned(BUILTIN_OWNER_ID, inputDefinitions);
    }

    @Override
    public void registerOwned(String ownerId, List<ToolDefinition> inputDefinitions) {
        ensureOpen();
        validateOwnerId(ownerId);
        Objects.requireNonNull(inputDefinitions, "definitions 不能为空");
        List<ToolDefinition> batch = List.copyOf(inputDefinitions);
        synchronized (lifecycleMonitor) {
            RegistrySnapshot current = snapshot;
            ToolOwnerState currentOwnerState = current.ownerStates().get(ownerId);
            if (currentOwnerState == ToolOwnerState.DRAINING) {
                throw new IllegalStateException("工具 owner 正在停止: " + ownerId);
            }
            java.util.HashSet<String> batchNames = new java.util.HashSet<>();
            for (ToolDefinition definition : batch) {
                if (!batchNames.add(definition.name())) {
                    throw new ToolRegistrationException(
                            definition.name(), "批量工具名称重复: " + definition.name(), null);
                }
                if (current.definitions().containsKey(definition.name())) {
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
            if (batch.isEmpty()) {
                return;
            }
            HashMap<String, ToolDefinition> nextDefinitions = new HashMap<>(current.definitions());
            HashMap<String, String> nextOwners = new HashMap<>(current.owners());
            HashMap<String, ToolOwnerState> nextStates = new HashMap<>(current.ownerStates());
            HashMap<String, Integer> nextInFlight = new HashMap<>(current.inFlight());
            for (ToolDefinition definition : batch) {
                nextDefinitions.put(definition.name(), definition);
                nextOwners.put(definition.name(), ownerId);
            }
            nextStates.put(ownerId, ToolOwnerState.ACTIVE);
            nextInFlight.putIfAbsent(ownerId, 0);
            snapshot = new RegistrySnapshot(nextDefinitions, nextOwners, nextStates, nextInFlight,
                    current.revision() + 1);
        }
    }

    @Override
    public void beginDrain(String ownerId) {
        ensureOpen();
        validateManagedOwnerId(ownerId);
        synchronized (lifecycleMonitor) {
            RegistrySnapshot current = snapshot;
            ToolOwnerState state = current.ownerStates().get(ownerId);
            if (state == null) {
                throw new IllegalArgumentException("工具 owner 未注册: " + ownerId);
            }
            if (state == ToolOwnerState.DRAINING) {
                return;
            }
            snapshot = current.withOwnerState(ownerId, ToolOwnerState.DRAINING);
            lifecycleMonitor.notifyAll();
        }
    }

    @Override
    public void unregisterOwned(String ownerId, Duration timeout) {
        ensureOpen();
        validateManagedOwnerId(ownerId);
        Objects.requireNonNull(timeout, "timeout 不能为空");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 不能为负数");
        }
        long remainingNanos = timeout.toNanos();
        synchronized (lifecycleMonitor) {
            RegistrySnapshot current = snapshot;
            if (current.ownerStates().get(ownerId) != ToolOwnerState.DRAINING) {
                throw new IllegalStateException("工具 owner 未处于 DRAINING 状态: " + ownerId);
            }
            while (snapshot.inFlight().getOrDefault(ownerId, 0) > 0) {
                if (remainingNanos <= 0) {
                    return;
                }
                long started = System.nanoTime();
                try {
                    TimeUnit.NANOSECONDS.timedWait(lifecycleMonitor, remainingNanos);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
                remainingNanos -= Math.max(0, System.nanoTime() - started);
            }
            RegistrySnapshot ready = snapshot;
            HashMap<String, ToolDefinition> nextDefinitions = new HashMap<>(ready.definitions());
            HashMap<String, String> nextOwners = new HashMap<>(ready.owners());
            ready.owners().forEach((name, registeredOwner) -> {
                if (ownerId.equals(registeredOwner)) {
                    nextDefinitions.remove(name);
                    nextOwners.remove(name);
                }
            });
            HashMap<String, ToolOwnerState> nextStates = new HashMap<>(ready.ownerStates());
            HashMap<String, Integer> nextInFlight = new HashMap<>(ready.inFlight());
            nextStates.remove(ownerId);
            nextInFlight.remove(ownerId);
            snapshot = new RegistrySnapshot(nextDefinitions, nextOwners, nextStates, nextInFlight,
                    ready.revision() + 1);
            lifecycleMonitor.notifyAll();
        }
    }

    @Override
    public long revision() {
        ensureOpen();
        return snapshot.revision();
    }

    @Override
    public Optional<ToolDefinition> find(String name) {
        ensureOpen();
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.definitions().get(name));
    }

    @Override
    public List<ToolDefinition> list() {
        ensureOpen();
        List<ToolDefinition> result = new ArrayList<>(snapshot.definitions().values());
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
        ToolDefinition definition;
        String ownerId;
        synchronized (lifecycleMonitor) {
            RegistrySnapshot current = snapshot;
            definition = current.definitions().get(call.name());
            if (definition == null) {
                ownerId = null;
            } else {
                ownerId = current.owners().get(call.name());
                if (current.ownerStates().get(ownerId) == ToolOwnerState.DRAINING) {
                    ToolException exception = new ToolException("工具 owner 正在停止: " + ownerId);
                    return finish(call, context, Optional.of(definition.riskLevel()), ToolResultStatus.FAILED,
                            NullNode.getInstance(), exception, started, false, argumentsSha256);
                }
                snapshot = current.withInFlight(ownerId, current.inFlight().getOrDefault(ownerId, 0) + 1);
            }
        }
        if (definition == null) {
            ToolNotFoundException exception = new ToolNotFoundException(call.name());
            return finish(call, context, Optional.empty(), ToolResultStatus.FAILED,
                    NullNode.getInstance(), exception, started, false, argumentsSha256);
        }

        try {
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
        } finally {
            releaseOwnerCall(ownerId);
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

    private void releaseOwnerCall(String ownerId) {
        if (ownerId == null) {
            return;
        }
        synchronized (lifecycleMonitor) {
            RegistrySnapshot current = snapshot;
            int currentInFlight = current.inFlight().getOrDefault(ownerId, 0);
            if (currentInFlight > 0) {
                snapshot = current.withInFlight(ownerId, currentInFlight - 1);
                lifecycleMonitor.notifyAll();
            }
        }
    }

    private static void validateOwnerId(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("ownerId 不能为空");
        }
    }

    private static void validateManagedOwnerId(String ownerId) {
        validateOwnerId(ownerId);
        if (BUILTIN_OWNER_ID.equals(ownerId)) {
            throw new IllegalArgumentException("builtin 工具不可停止或撤销");
        }
    }

    /** 一次性发布工具定义、owner 状态和在途计数。 */
    private record RegistrySnapshot(
            Map<String, ToolDefinition> definitions,
            Map<String, String> owners,
            Map<String, ToolOwnerState> ownerStates,
            Map<String, Integer> inFlight,
            long revision) {

        private RegistrySnapshot {
            definitions = Map.copyOf(definitions);
            owners = Map.copyOf(owners);
            ownerStates = Map.copyOf(ownerStates);
            inFlight = Map.copyOf(inFlight);
        }

        private static RegistrySnapshot empty() {
            return new RegistrySnapshot(Map.of(), Map.of(), Map.of(), Map.of(), 0);
        }

        private RegistrySnapshot withOwnerState(String ownerId, ToolOwnerState state) {
            HashMap<String, ToolOwnerState> nextStates = new HashMap<>(ownerStates);
            nextStates.put(ownerId, state);
            return new RegistrySnapshot(definitions, owners, nextStates, inFlight, revision + 1);
        }

        private RegistrySnapshot withInFlight(String ownerId, int count) {
            HashMap<String, Integer> nextInFlight = new HashMap<>(inFlight);
            nextInFlight.put(ownerId, count);
            return new RegistrySnapshot(definitions, owners, ownerStates, nextInFlight, revision);
        }
    }
}
