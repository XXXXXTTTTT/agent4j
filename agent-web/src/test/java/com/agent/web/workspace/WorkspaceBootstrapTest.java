package com.agent.web.workspace;

import com.agent.web.identity.Actor;
import com.agent.web.identity.ActorResolver;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceBootstrapTest {

    @Test
    void createsConfiguredOwnerWorkspaceOnApplicationStartup() throws Exception {
        WorkspaceAccessService accessService = mock(WorkspaceAccessService.class);
        ActorResolver actorResolver = mock(ActorResolver.class);
        Actor actor = new Actor("local-user", "本地用户");
        UUID workspaceId = UUID.fromString("52e7fe8c-2759-4cf3-945f-b927af625fbe");
        Path workspacePath = Path.of("D:/agent4j");
        when(actorResolver.current()).thenReturn(actor);
        WorkspaceBootstrap bootstrap = new WorkspaceBootstrap(
                accessService,
                actorResolver,
                workspacePath,
                "agent4j",
                () -> workspaceId);

        bootstrap.run(null);

        verify(accessService).ensureDefaultWorkspace(
                actor, workspaceId, "agent4j", workspacePath, "agent4j");
    }
}
