package com.agent.web.workspace;

import com.agent.web.identity.Actor;
import com.agent.web.identity.ActorResolver;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** 在生产应用启动时幂等创建配置用户与默认工作区。 */
public final class WorkspaceBootstrap implements ApplicationRunner {

    private final WorkspaceAccessService accessService;
    private final ActorResolver actorResolver;
    private final Path workspacePath;
    private final String repositoryId;
    private final Supplier<UUID> workspaceIdSupplier;

    /** 创建默认工作区启动器。 */
    public WorkspaceBootstrap(
            WorkspaceAccessService accessService,
            ActorResolver actorResolver,
            Path workspacePath,
            String repositoryId) {
        this(accessService, actorResolver, workspacePath, repositoryId, UUID::randomUUID);
    }

    WorkspaceBootstrap(
            WorkspaceAccessService accessService,
            ActorResolver actorResolver,
            Path workspacePath,
            String repositoryId,
            Supplier<UUID> workspaceIdSupplier) {
        this.accessService = Objects.requireNonNull(accessService, "accessService 不能为空");
        this.actorResolver = Objects.requireNonNull(actorResolver, "actorResolver 不能为空");
        this.workspacePath = Objects.requireNonNull(workspacePath, "workspacePath 不能为空");
        this.repositoryId = requireText(repositoryId, "repositoryId");
        this.workspaceIdSupplier = Objects.requireNonNull(
                workspaceIdSupplier, "workspaceIdSupplier 不能为空");
    }

    @Override
    public void run(ApplicationArguments args) {
        Actor actor = actorResolver.current();
        accessService.ensureDefaultWorkspace(
                actor,
                workspaceIdSupplier.get(),
                repositoryId,
                workspacePath,
                repositoryId);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }
}
