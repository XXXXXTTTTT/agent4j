package com.agent.core.cli;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.InterruptPolicy;
import com.agent.core.engine.InterruptRequest;
import com.agent.core.intent.RequiredCapability;
import com.agent.core.nodes.CoderNode;
import com.agent.core.nodes.OpsNode;
import com.agent.core.nodes.PlannerNode;
import com.agent.sandbox.pty.TerminalTarget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 把结构化 CLI 授权决策接入图执行前的 HITL 中断。 */
public final class CliApprovalInterruptPolicy implements InterruptPolicy {

    private static final String OPS_NODE = "ops";

    private final CliCommandCatalog catalog;
    private final TerminalTarget target;
    private final Duration timeout;
    private final ObjectMapper objectMapper;

    /** 创建绑定命令目录、终端目标和执行上限的审批策略。 */
    public CliApprovalInterruptPolicy(
            CliCommandCatalog catalog,
            TerminalTarget target,
            Duration timeout,
            ObjectMapper objectMapper) {
        this.catalog = Objects.requireNonNull(catalog, "catalog 不能为空");
        this.target = Objects.requireNonNull(target, "target 不能为空");
        this.timeout = Objects.requireNonNull(timeout, "timeout 不能为空");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须大于 0");
        }
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /** 只在 ops 节点对目录授权结果进行中断决策。 */
    @Override
    public Optional<InterruptRequest> evaluate(
            UUID runId,
            String nodeName,
            AgentState state) {
        Objects.requireNonNull(runId, "runId 不能为空");
        Objects.requireNonNull(nodeName, "nodeName 不能为空");
        Objects.requireNonNull(state, "state 不能为空");
        if (!OPS_NODE.equals(nodeName)) {
            return Optional.empty();
        }
        ParsedIntent parsed = parse(state);
        CliAuthorization authorization = catalog.authorize(
                parsed.intent(), authorizationContext(parsed.capabilities(), false));
        return switch (authorization.decision()) {
            case ALLOWED -> Optional.empty();
            case DENIED -> throw new IllegalStateException(
                    "CLI 命令授权被拒绝: " + authorization.reason());
            case APPROVAL_REQUIRED -> Optional.of(
                    interrupt(runId, nodeName, parsed, authorization));
        };
    }

    /** 按图引擎的一次性批准恢复信号重新生成并校验授权计划。 */
    public CliAuthorization authorizeForExecution(
            AgentState state,
            boolean approvalBypassed) {
        ParsedIntent parsed = parse(state);
        return catalog.authorize(
                parsed.intent(), authorizationContext(
                        parsed.capabilities(), approvalBypassed));
    }

    private ParsedIntent parse(AgentState state) {
        String name = requireVariable(state, OpsNode.COMMAND_NAME_KEY);
        List<String> arguments = parseArguments(
                name, requireVariable(state, OpsNode.COMMAND_ARGUMENTS_KEY));
        Path workspace = Path.of(requireVariable(state, CoderNode.WORKSPACE_PATH_KEY));
        Set<RequiredCapability> capabilities = parseCapabilities(
                state.variables().get(PlannerNode.REQUIRED_CAPABILITIES_KEY));
        return new ParsedIntent(
                new CliCommandIntent(name, arguments, workspace, target, timeout),
                capabilities,
                objectMapper.valueToTree(arguments).toString());
    }

    private List<String> parseArguments(String commandName, String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isArray()) {
                throw new CliArgumentException(
                        commandName, -1, OpsNode.COMMAND_ARGUMENTS_KEY + " 必须是 JSON 数组");
            }
            List<String> arguments = new ArrayList<>();
            for (int index = 0; index < root.size(); index++) {
                JsonNode value = root.get(index);
                if (!value.isTextual()) {
                    throw new CliArgumentException(
                            commandName, index, "CLI 参数必须是字符串");
                }
                arguments.add(value.textValue());
            }
            return List.copyOf(arguments);
        } catch (CliArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new CliArgumentException(
                    commandName, -1, OpsNode.COMMAND_ARGUMENTS_KEY + " 不是合法 JSON", exception);
        }
    }

    private Set<RequiredCapability> parseCapabilities(String value) {
        if (value == null || value.isEmpty()) {
            return Set.of();
        }
        EnumSet<RequiredCapability> capabilities = EnumSet.noneOf(RequiredCapability.class);
        for (String name : value.split(",", -1)) {
            try {
                capabilities.add(RequiredCapability.valueOf(name));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        PlannerNode.REQUIRED_CAPABILITIES_KEY
                                + " 包含非法能力名称: " + name,
                        exception);
            }
        }
        return Set.copyOf(capabilities);
    }

    private CliAuthorizationContext authorizationContext(
            Set<RequiredCapability> capabilities,
            boolean approved) {
        return new CliAuthorizationContext(capabilities, approved, approved);
    }

    private InterruptRequest interrupt(
            UUID runId,
            String nodeName,
            ParsedIntent parsed,
            CliAuthorization authorization) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("commandName", authorization.plan().name());
        details.put("commandArguments", parsed.argumentsJson());
        details.put("command", authorization.plan().request().bashCommand());
        details.put("riskLevel", authorization.plan().riskLevel().name());
        details.put("commandSha256", authorization.plan().commandSha256());
        details.put("authorizationReason", authorization.reason());
        UUID interruptId = UUID.nameUUIDFromBytes((runId + ":" + nodeName + ":"
                + authorization.plan().commandSha256()).getBytes(StandardCharsets.UTF_8));
        return new InterruptRequest(
                interruptId,
                nodeName,
                authorization.reason(),
                Map.copyOf(details));
    }

    private String requireVariable(AgentState state, String key) {
        String value = state.variables().get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少状态变量: " + key);
        }
        return value;
    }

    private record ParsedIntent(
            CliCommandIntent intent,
            Set<RequiredCapability> capabilities,
            String argumentsJson) {
    }

}
