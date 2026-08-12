package com.agent.web.skill;

import com.agent.core.tool.ToolRegistry;
import com.agent.web.capability.CapabilityManagementAuditEvent;
import com.agent.web.capability.CapabilityManagementAuditSink;
import com.agent.web.capability.InstallationScope;
import com.agent.web.identity.Actor;
import com.agent.web.identity.ActorResolver;
import com.agent.web.workspace.WorkspaceAccessService;
import com.agent.web.workspace.WorkspacePermission;
import com.agent.web.workspace.WorkspaceRecord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** GitHub Skill 预览、确认和范围隔离服务。 */
public final class GitHubSkillInstallationService {
    private final GitHubSkillCatalogClient client;
    private final ToolRegistry toolRegistry;
    private final ActorResolver actorResolver;
    private final WorkspaceAccessService workspaceAccess;
    private final SkillInstallationRepository repository;
    private final CapabilityManagementAuditSink auditSink;
    private final Clock clock;
    private final Duration previewTtl;
    private final Supplier<UUID> uuidSupplier;
    private final Map<UUID, PendingPreview> previews = new ConcurrentHashMap<>();

    public GitHubSkillInstallationService(
            GitHubSkillCatalogClient client,
            ToolRegistry toolRegistry,
            ActorResolver actorResolver,
            WorkspaceAccessService workspaceAccess,
            SkillInstallationRepository repository,
            CapabilityManagementAuditSink auditSink,
            Clock clock,
            Duration previewTtl,
            Supplier<UUID> uuidSupplier) {
        this.client = Objects.requireNonNull(client, "client 不能为空");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry 不能为空");
        this.actorResolver = Objects.requireNonNull(actorResolver, "actorResolver 不能为空");
        this.workspaceAccess = Objects.requireNonNull(workspaceAccess, "workspaceAccess 不能为空");
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        if (previewTtl == null || previewTtl.isZero() || previewTtl.isNegative()) {
            throw new IllegalArgumentException("previewTtl 必须大于零");
        }
        this.previewTtl = previewTtl;
        this.uuidSupplier = Objects.requireNonNull(uuidSupplier, "uuidSupplier 不能为空");
    }

    /** 仅从 GitHub 读取固定提交内容，创建不落库预览。 */
    public SkillInstallationPreview preview(
            UUID requestWorkspaceId,
            String repositoryName,
            InstallationScope requestedScope,
            UUID targetWorkspaceId) {
        Actor actor = actorResolver.current();
        ScopeTarget target = resolveTarget(actor, requestWorkspaceId, requestedScope, targetWorkspaceId);
        Set<String> registeredTools = toolRegistry.list().stream().map(definition -> definition.name()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        GitHubSkillSnapshot snapshot = client.readSkill(repositoryName, registeredTools);
        Instant now = clock.instant();
        UUID previewId = uuidSupplier.get();
        String token = uuidSupplier.get().toString();
        PendingPreview pending = new PendingPreview(actor.userId(), target, snapshot, token, now.plus(previewTtl));
        previews.put(previewId, pending);
        return new SkillInstallationPreview(previewId, token, snapshot.repositoryUrl(), snapshot.repository(),
                snapshot.commitSha(), snapshot.blobSha(), snapshot.path(), snapshot.license(), snapshot.contentSha256(),
                snapshot.summary(), snapshot.requestedToolNames(), target.scope(), target.workspaceId(), true, true,
                pending.expiresAt());
    }

    /** 一次性确认并保存已审查的不可变 Skill 快照及安装记录。 */
    public SkillInstallationRecord confirm(
            UUID requestWorkspaceId,
            UUID previewId,
            String confirmationToken,
            InstallationScope requestedScope,
            UUID targetWorkspaceId) {
        Objects.requireNonNull(previewId, "previewId 不能为空");
        Actor actor = actorResolver.current();
        ScopeTarget target = resolveTarget(actor, requestWorkspaceId, requestedScope, targetWorkspaceId);
        PendingPreview pending = previews.remove(previewId);
        if (pending == null || !pending.actorUserId().equals(actor.userId())
                || !pending.target().equals(target)
                || !pending.confirmationToken().equals(confirmationToken)
                || clock.instant().isAfter(pending.expiresAt())) {
            throw new InvalidConfirmationException();
        }
        Instant now = clock.instant();
        SkillSnapshotRecord snapshot = repository.saveSnapshot(new SkillSnapshotRecord(
                uuidSupplier.get(), pending.snapshot().repositoryUrl(), pending.snapshot().repository(),
                pending.snapshot().commitSha(), pending.snapshot().blobSha(), pending.snapshot().path(),
                pending.snapshot().license(), pending.snapshot().contentSha256(), pending.snapshot().summary(),
                pending.snapshot().requestedToolNames(), pending.snapshot().content(), now));
        SkillInstallationRecord installation = repository.saveInstallation(new SkillInstallationRecord(
                uuidSupplier.get(), snapshot.skillSnapshotId(), target.scope(), target.workspaceId(), actor.userId(),
                SkillInstallationStatus.APPROVED, sha256(confirmationToken), now, now, now));
        auditSink.record(new CapabilityManagementAuditEvent("SKILL_INSTALLATION_CONFIRMED", actor.userId(),
                requestWorkspaceId, null, installation.skillInstallationId(), null,
                snapshot.commitSha(), "SUCCESS", now));
        return installation;
    }

    public List<SkillInstallationRecord> list(UUID workspaceId) {
        Actor actor = actorResolver.current();
        workspaceAccess.requireWorkspace(workspaceId, actor.userId(), WorkspacePermission.VIEWER);
        return repository.findInstallations(actor.userId(), workspaceId);
    }

    /** 撤销当前主体可管理范围内的 Skill 安装。 */
    public SkillInstallationRecord uninstall(UUID workspaceId, UUID skillInstallationId) {
        Objects.requireNonNull(skillInstallationId, "skillInstallationId 不能为空");
        Actor actor = actorResolver.current();
        workspaceAccess.requireWorkspace(workspaceId, actor.userId(), WorkspacePermission.OPERATOR);
        SkillInstallationRecord installation = repository.findInstallations(actor.userId(), workspaceId).stream()
                .filter(value -> value.skillInstallationId().equals(skillInstallationId))
                .findFirst()
                .orElseThrow(() -> new InstallationNotFoundException(skillInstallationId));
        if (!repository.deleteInstallation(skillInstallationId, actor.userId(), workspaceId)) {
            throw new InstallationNotFoundException(skillInstallationId);
        }
        auditSink.record(new CapabilityManagementAuditEvent("SKILL_INSTALLATION_REMOVED", actor.userId(),
                workspaceId, null, skillInstallationId, null, "", "SUCCESS", clock.instant()));
        return installation;
    }

    private ScopeTarget resolveTarget(Actor actor, UUID requestWorkspaceId, InstallationScope requestedScope, UUID targetWorkspaceId) {
        Objects.requireNonNull(requestWorkspaceId, "workspaceId 不能为空");
        InstallationScope scope = requestedScope == null ? InstallationScope.WORKSPACE : requestedScope;
        WorkspaceRecord requestWorkspace = workspaceAccess.requireWorkspace(requestWorkspaceId, actor.userId(), WorkspacePermission.OPERATOR);
        if (scope == InstallationScope.WORKSPACE) {
            UUID workspaceId = targetWorkspaceId == null ? requestWorkspaceId : targetWorkspaceId;
            workspaceAccess.requireWorkspace(workspaceId, actor.userId(), WorkspacePermission.OPERATOR);
            return new ScopeTarget(scope, workspaceId);
        }
        if (targetWorkspaceId != null) throw new IllegalArgumentException("USER_GLOBAL 安装的 targetWorkspaceId 必须为空");
        if (!requestWorkspace.ownerUserId().equals(actor.userId())) throw new IllegalArgumentException("USER_GLOBAL 安装只能从用户自己的工作区发起");
        return new ScopeTarget(scope, null);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算确认令牌摘要", exception);
        }
    }

    private record ScopeTarget(InstallationScope scope, UUID workspaceId) { }
    private record PendingPreview(String actorUserId, ScopeTarget target, GitHubSkillSnapshot snapshot, String confirmationToken, Instant expiresAt) { }

    public static final class InvalidConfirmationException extends RuntimeException {
        public InvalidConfirmationException() { super("Skill 安装确认无效或已过期"); }
    }

    /** 当前主体范围内不存在该 Skill 安装。 */
    public static final class InstallationNotFoundException extends RuntimeException {
        public InstallationNotFoundException(UUID skillInstallationId) {
            super("Skill 安装不存在或当前用户无权访问: " + skillInstallationId);
        }
    }
}
