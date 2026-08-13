package com.agent.web.mcp.installation;

import com.agent.web.capability.CapabilityManagementAuditEvent;
import com.agent.web.capability.CapabilityManagementAuditSink;
import com.agent.web.capability.InstallationScope;
import com.agent.web.identity.Actor;
import com.agent.web.identity.ActorResolver;
import com.agent.web.mcp.catalog.OfficialMcpServerRecord;
import com.agent.web.workspace.WorkspaceAccessService;
import com.agent.web.workspace.WorkspacePermission;
import com.agent.web.workspace.WorkspaceRecord;
import com.agent.core.intent.RequiredCapability;
import com.agent.core.tool.ToolRiskLevel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** 创建无副作用预览，并在明确确认后保存 MCP 安装。 */
public final class McpInstallationService {
    private static final ObjectMapper CONFIRMATION_MAPPER = new ObjectMapper();
    private final ActorResolver actorResolver;
    private final WorkspaceAccessService workspaceAccess;
    private final McpInstallationRepository repository;
    private final CapabilityManagementAuditSink auditSink;
    private final Clock clock;
    private final Duration previewTtl;
    private final Supplier<UUID> uuidSupplier;
    private final String runtimeImage;
    private final Map<UUID, PendingPreview> previews = new ConcurrentHashMap<>();

    public McpInstallationService(
            ActorResolver actorResolver,
            WorkspaceAccessService workspaceAccess,
            McpInstallationRepository repository,
            CapabilityManagementAuditSink auditSink,
            Clock clock,
            Duration previewTtl,
            Supplier<UUID> uuidSupplier) {
        this(actorResolver, workspaceAccess, repository, auditSink, clock, previewTtl, uuidSupplier, "");
    }

    /** 创建确认服务并冻结经运行时配置校验的 Docker 镜像。 */
    public McpInstallationService(
            ActorResolver actorResolver,
            WorkspaceAccessService workspaceAccess,
            McpInstallationRepository repository,
            CapabilityManagementAuditSink auditSink,
            Clock clock,
            Duration previewTtl,
            Supplier<UUID> uuidSupplier,
            String runtimeImage) {
        this.actorResolver = Objects.requireNonNull(actorResolver, "actorResolver 不能为空");
        this.workspaceAccess = Objects.requireNonNull(workspaceAccess, "workspaceAccess 不能为空");
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        this.previewTtl = positive(previewTtl, "previewTtl");
        this.uuidSupplier = Objects.requireNonNull(uuidSupplier, "uuidSupplier 不能为空");
        this.runtimeImage = runtimeImage == null ? "" : runtimeImage.trim();
    }

    /** 创建预览，不写库、不下载、不启动进程。 */
    public McpInstallationPreview preview(
            UUID requestWorkspaceId,
            OfficialMcpServerRecord server,
            InstallationScope requestedScope,
            UUID targetWorkspaceId) {
        return preview(requestWorkspaceId, server, requestedScope, targetWorkspaceId, ToolRiskLevel.HIGH,
                java.util.Set.of(RequiredCapability.TOOL), WorkspaceMountMode.NONE);
    }

    /** 预览明确冻结风险、能力、挂载、无网络和运行镜像治理策略。 */
    public McpInstallationPreview preview(
            UUID requestWorkspaceId,
            OfficialMcpServerRecord server,
            InstallationScope requestedScope,
            UUID targetWorkspaceId,
            ToolRiskLevel riskLevel,
            java.util.Set<RequiredCapability> requiredCapabilities,
            WorkspaceMountMode workspaceMountMode) {
        Actor actor = actorResolver.current();
        ScopeTarget target = resolveTarget(actor, requestWorkspaceId, requestedScope, targetWorkspaceId);
        Governance governance = governance(riskLevel, requiredCapabilities, workspaceMountMode);
        if (runtimeImage.isBlank()) throw new IllegalStateException("MCP 运行镜像未配置");
        Instant now = clock.instant();
        UUID previewId = uuidSupplier.get();
        String confirmationToken = uuidSupplier.get() + "." + confirmationSummary(server, governance, runtimeImage);
        PendingPreview pending = new PendingPreview(actor.userId(), target, server, governance, confirmationToken, now.plus(previewTtl));
        previews.put(previewId, pending);
        return new McpInstallationPreview(previewId, confirmationToken, target.scope(), target.workspaceId(),
                server.sourceUrl(), server.commitSha(), server.metadataSha256(), server.command(), server.arguments(),
                server.environmentVariableNames(), governance.riskLevel(), governance.requiredCapabilities(),
                governance.workspaceMountMode(), McpNetworkMode.NONE, runtimeImage, server.readmeSummary(), true, true,
                pending.expiresAt());
    }

    /** 只接受与未过期预览完全一致的一次性确认。 */
    public McpInstallationRecord confirm(
            UUID requestWorkspaceId,
            UUID previewId,
            String confirmationToken,
            InstallationScope requestedScope,
            UUID targetWorkspaceId) {
        Objects.requireNonNull(previewId, "previewId 不能为空");
        Actor actor = actorResolver.current();
        ScopeTarget target = resolveTarget(actor, requestWorkspaceId, requestedScope, targetWorkspaceId);
        PendingPreview pending = previews.get(previewId);
        if (pending == null || !pending.actorUserId().equals(actor.userId()) || !pending.target().equals(target)
                || !pending.confirmationToken().equals(confirmationToken) || clock.instant().isAfter(pending.expiresAt())) {
            throw new InvalidConfirmationException();
        }
        Instant now = clock.instant();
        McpSourceSnapshot snapshot = McpSourceSnapshot.from(uuidSupplier.get(), pending.server(), now);
        McpInstallationRecord installation = new McpInstallationRecord(
                uuidSupplier.get(), snapshot.snapshotId(), target.scope(), target.workspaceId(), actor.userId(),
                McpInstallationStatus.STOPPED, sha256(confirmationToken), now, now, now, pending.governance().riskLevel(),
                pending.governance().requiredCapabilities(), pending.governance().workspaceMountMode(), McpNetworkMode.NONE,
                runtimeImage, true, null, null, null, 0);
        repository.confirmInstallation(new McpInstallationCommand(snapshot, installation,
                new CapabilityManagementAuditEvent("MCP_INSTALLATION_CONFIRMED", actor.userId(),
                        requestWorkspaceId, installation.installationId(), null, null, snapshot.commitSha(), "SUCCESS", now)));
        previews.remove(previewId, pending);
        return installation;
    }

    /** 返回当前主体在工作区和其用户全局范围内的安装。 */
    public List<McpInstallationRecord> list(UUID workspaceId) {
        Actor actor = actorResolver.current();
        workspaceAccess.requireWorkspace(workspaceId, actor.userId(), WorkspacePermission.VIEWER);
        return repository.findInstallations(actor.userId(), workspaceId);
    }

    /** 返回当前主体可见的安装及已冻结环境变量名，不返回变量值。 */
    public List<McpInstallationDetails> listDetails(UUID workspaceId) {
        Actor actor = actorResolver.current();
        workspaceAccess.requireWorkspace(workspaceId, actor.userId(), WorkspacePermission.VIEWER);
        return repository.findInstallationDetails(actor.userId(), workspaceId);
    }

    /** 撤销当前主体可管理范围内的安装记录，不启动或停止运行时。 */
    public McpInstallationRecord uninstall(UUID workspaceId, UUID installationId, long expectedVersion) {
        Objects.requireNonNull(installationId, "installationId 不能为空");
        Actor actor = actorResolver.current();
        workspaceAccess.requireWorkspace(workspaceId, actor.userId(), WorkspacePermission.OPERATOR);
        McpInstallationRecord installation = repository.findInstallations(actor.userId(), workspaceId).stream()
                .filter(value -> value.installationId().equals(installationId))
                .findFirst()
                .orElseThrow(() -> new InstallationNotFoundException(installationId));
        return repository.removeInstallation(installationId, actor.userId(), workspaceId, expectedVersion,
                new CapabilityManagementAuditEvent("MCP_INSTALLATION_REMOVED", actor.userId(), workspaceId,
                        installationId, null, null, "", "SUCCESS", clock.instant()));
    }

    /** 兼容旧内部调用；HTTP 入口必须传递调用方的 expectedVersion。 */
    @Deprecated
    public McpInstallationRecord uninstall(UUID workspaceId, UUID installationId) {
        Objects.requireNonNull(installationId, "installationId 不能为空");
        Actor actor = actorResolver.current();
        workspaceAccess.requireWorkspace(workspaceId, actor.userId(), WorkspacePermission.OPERATOR);
        McpInstallationRecord installation = repository.findInstallations(actor.userId(), workspaceId).stream()
                .filter(value -> value.installationId().equals(installationId))
                .findFirst()
                .orElseThrow(() -> new InstallationNotFoundException(installationId));
        return uninstall(workspaceId, installationId, installation.version());
    }

    private ScopeTarget resolveTarget(
            Actor actor,
            UUID requestWorkspaceId,
            InstallationScope requestedScope,
            UUID targetWorkspaceId) {
        Objects.requireNonNull(requestWorkspaceId, "workspaceId 不能为空");
        InstallationScope scope = requestedScope == null ? InstallationScope.WORKSPACE : requestedScope;
        WorkspaceRecord requestWorkspace = workspaceAccess.requireWorkspace(
                requestWorkspaceId, actor.userId(), WorkspacePermission.OPERATOR);
        if (scope == InstallationScope.WORKSPACE) {
            UUID workspaceId = targetWorkspaceId == null ? requestWorkspaceId : targetWorkspaceId;
            workspaceAccess.requireWorkspace(workspaceId, actor.userId(), WorkspacePermission.OPERATOR);
            return new ScopeTarget(scope, workspaceId);
        }
        if (targetWorkspaceId != null) {
            throw new IllegalArgumentException("USER_GLOBAL 安装的 targetWorkspaceId 必须为空");
        }
        if (!requestWorkspace.ownerUserId().equals(actor.userId())) {
            throw new IllegalArgumentException("USER_GLOBAL 安装只能从用户自己的工作区发起");
        }
        return new ScopeTarget(scope, null);
    }

    private static Duration positive(Duration value, String field) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(field + " 必须大于零");
        }
        return value;
    }

    private static Governance governance(ToolRiskLevel riskLevel, java.util.Set<RequiredCapability> requiredCapabilities,
                                         WorkspaceMountMode workspaceMountMode) {
        ToolRiskLevel resolvedRisk = Objects.requireNonNull(riskLevel, "riskLevel 不能为空");
        java.util.Set<RequiredCapability> resolvedCapabilities = java.util.Set.copyOf(
                Objects.requireNonNull(requiredCapabilities, "requiredCapabilities 不能为空"));
        WorkspaceMountMode resolvedMount = Objects.requireNonNull(workspaceMountMode, "workspaceMountMode 不能为空");
        if (!resolvedCapabilities.contains(RequiredCapability.TOOL)) {
            throw new IllegalArgumentException("requiredCapabilities 必须包含 TOOL");
        }
        if (resolvedMount == WorkspaceMountMode.READ_ONLY && !resolvedCapabilities.contains(RequiredCapability.CODE_READ)) {
            throw new IllegalArgumentException("READ_ONLY 挂载必须声明 CODE_READ 能力");
        }
        if (resolvedMount == WorkspaceMountMode.READ_WRITE && !resolvedCapabilities.contains(RequiredCapability.CODE_WRITE)) {
            throw new IllegalArgumentException("READ_WRITE 挂载必须声明 CODE_WRITE 能力");
        }
        return new Governance(resolvedRisk, resolvedCapabilities, resolvedMount);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算确认令牌摘要", exception);
        }
    }

    /** 确认令牌使用固定字段顺序的 JSON，覆盖全部不可变来源、运行时和治理输入。 */
    private static String confirmationSummary(OfficialMcpServerRecord server, Governance governance, String runtimeImage) {
        ObjectNode root = CONFIRMATION_MAPPER.createObjectNode();
        ObjectNode snapshot = root.putObject("snapshot");
        snapshot.put("commitSha", server.commitSha());
        ObjectNode blobShas = snapshot.putObject("blobShas");
        new java.util.TreeMap<>(server.blobShas()).forEach(blobShas::put);
        root.put("metadataSha256", server.metadataSha256());
        root.put("command", server.command());
        array(root, "arguments", server.arguments());
        root.put("launchBin", server.launchBin());
        array(root, "environmentVariableNames", server.environmentVariableNames());
        root.put("runtimeImage", runtimeImage);
        root.put("riskLevel", governance.riskLevel().name());
        array(root, "capabilities", governance.requiredCapabilities().stream().map(Enum::name).sorted().toList());
        root.put("workspaceMountMode", governance.workspaceMountMode().name());
        root.put("networkMode", McpNetworkMode.NONE.name());
        try {
            return sha256(CONFIRMATION_MAPPER.writeValueAsString(root));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化确认令牌摘要", exception);
        }
    }

    private static void array(ObjectNode target, String field, List<String> values) {
        ArrayNode array = target.putArray(field);
        values.forEach(array::add);
    }

    private record ScopeTarget(InstallationScope scope, UUID workspaceId) { }
    private record PendingPreview(String actorUserId, ScopeTarget target, OfficialMcpServerRecord server, Governance governance,
                                  String confirmationToken, Instant expiresAt) { }
    private record Governance(ToolRiskLevel riskLevel, java.util.Set<RequiredCapability> requiredCapabilities,
                              WorkspaceMountMode workspaceMountMode) { }

    /** 预览不存在、已过期、已使用或确认请求与预览不一致。 */
    public static final class InvalidConfirmationException extends RuntimeException {
        public InvalidConfirmationException() {
            super("MCP 安装确认无效或已过期");
        }
    }

    /** 当前主体范围内不存在该安装。 */
    public static final class InstallationNotFoundException extends RuntimeException {
        public InstallationNotFoundException(UUID installationId) {
            super("MCP 安装不存在或当前用户无权访问: " + installationId);
        }
    }
}
