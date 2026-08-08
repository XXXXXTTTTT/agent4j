package com.agent.core.cli;

import com.agent.core.intent.RequiredCapability;
import com.agent.sandbox.pty.CommandRequest;
import com.agent.sandbox.pty.DockerTarget;
import com.agent.sandbox.pty.PtyTarget;
import com.agent.sandbox.pty.TerminalTarget;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 只读 CLI 命令目录和授权策略。 */
public final class CliCommandCatalog {

    private final List<CliCommandDefinition> definitions;
    private final Map<String, CliCommandDefinition> definitionsByName;

    /** 构造一次性不可变命令目录。 */
    public CliCommandCatalog(List<CliCommandDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions 不能为空");
        List<CliCommandDefinition> snapshot = List.copyOf(definitions);
        Map<String, CliCommandDefinition> byName = new HashMap<>();
        for (CliCommandDefinition definition : snapshot) {
            Objects.requireNonNull(definition, "命令定义不能为 null");
            if (byName.putIfAbsent(definition.name(), definition) != null) {
                throw new CliCommandDefinitionException(
                        definition.name(), "命令名重复: " + definition.name());
            }
        }
        this.definitions = snapshot;
        this.definitionsByName = Map.copyOf(byName);
    }

    /** 返回目录定义快照。 */
    public List<CliCommandDefinition> list() {
        return definitions;
    }

    /** 使用区分大小写的精确名称查找命令。 */
    public Optional<CliCommandDefinition> find(String name) {
        return Optional.ofNullable(definitionsByName.get(name));
    }

    /** 对结构化意图执行目录查找、边界检查、渲染和授权。 */
    public CliAuthorization authorize(
            CliCommandIntent intent,
            CliAuthorizationContext context) {
        Objects.requireNonNull(intent, "intent 不能为空");
        Objects.requireNonNull(context, "context 不能为空");
        CliCommandDefinition definition = find(intent.name())
                .orElseThrow(() -> new CliCommandNotFoundException(intent.name()));

        validateArguments(intent, definition);
        validateWorkspace(intent);
        String bashCommand = render(definition, intent.arguments());
        CliCommandPlan plan = new CliCommandPlan(
                intent.name(),
                new CommandRequest(intent.target(), bashCommand, intent.timeout()),
                definition.riskLevel(),
                sha256(bashCommand));

        List<RequiredCapability> missingCapabilities = definition.requiredCapabilities().stream()
                .filter(capability -> !context.grantedCapabilities().contains(capability))
                .toList();
        if (!missingCapabilities.isEmpty()) {
            return new CliAuthorization(
                    CliAuthorizationDecision.DENIED,
                    "缺少能力: " + missingCapabilities,
                    plan);
        }

        return switch (definition.riskLevel()) {
            case READ_ONLY -> new CliAuthorization(
                    CliAuthorizationDecision.ALLOWED,
                    "只读命令自动允许",
                    plan);
            case MUTATING -> new CliAuthorization(
                    context.userApproved()
                            ? CliAuthorizationDecision.ALLOWED
                            : CliAuthorizationDecision.APPROVAL_REQUIRED,
                    context.userApproved() ? "用户已批准" : "等待用户批准",
                    plan);
            case DESTRUCTIVE -> new CliAuthorization(
                    context.userApproved() && context.administratorApproved()
                            ? CliAuthorizationDecision.ALLOWED
                            : CliAuthorizationDecision.APPROVAL_REQUIRED,
                    context.userApproved() && context.administratorApproved()
                            ? "用户和管理员均已批准"
                            : "等待用户和管理员批准",
                    plan);
        };
    }

    private static void validateArguments(
            CliCommandIntent intent,
            CliCommandDefinition definition) {
        for (int index = 0; index < intent.arguments().size(); index++) {
            CliValidation.validateIntentArgument(
                    definition.name(), intent.arguments().get(index), index);
        }
    }

    private static void validateWorkspace(CliCommandIntent intent) {
        Path root = realPath(
                intent.workspaceRoot(),
                intent.workspaceRoot(),
                null,
                "workspaceRoot 无法解析");
        Path targetPath = targetWorkspace(intent.target());
        Path target = realPath(
                intent.workspaceRoot(),
                targetPath,
                targetPath,
                "目标工作目录无法解析");
        if (!target.startsWith(root)) {
            throw new CliWorkspaceViolationException(
                    intent.workspaceRoot(),
                    targetPath,
                    "目标工作目录超出 workspaceRoot");
        }
    }

    private static Path targetWorkspace(TerminalTarget target) {
        return switch (target) {
            case PtyTarget ptyTarget -> ptyTarget.workingDirectory();
            case DockerTarget dockerTarget -> dockerTarget.hostWorkspace();
        };
    }

    private static Path realPath(
            Path root,
            Path path,
            Path targetPath,
            String message) {
        try {
            return path.toRealPath();
        } catch (IOException exception) {
            throw new CliWorkspaceViolationException(root, targetPath, message, exception);
        }
    }

    private static String render(
            CliCommandDefinition definition,
            List<String> arguments) {
        List<String> tokens = new ArrayList<>(1 + definition.fixedArguments().size() + arguments.size());
        tokens.add(definition.executable());
        tokens.addAll(definition.fixedArguments());
        tokens.addAll(arguments);
        return tokens.stream().map(CliCommandCatalog::quoteToken).reduce(
                (left, right) -> left + " " + right).orElseThrow();
    }

    private static String quoteToken(String token) {
        return "'" + token.replace("'", "'\\''") + "'";
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 必须提供 SHA-256", exception);
        }
    }
}
