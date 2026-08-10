package com.agent.web.controller;

import com.agent.web.workspace.WorkspacePermission;
import com.agent.web.workspace.WorkspaceRecord;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceViewTest {

    @Test
    void exposesWorkspacePathAsExactServerPathString() {
        Instant now = Instant.parse("2026-08-10T00:00:00Z");
        WorkspaceRecord workspace = new WorkspaceRecord(
                UUID.fromString("43ff748f-0d51-40c8-b8d8-4af68a72a4e4"),
                "local",
                "local",
                Path.of("/agent-workspace"),
                "local",
                WorkspacePermission.OWNER,
                now,
                now);

        WorkspaceView view = WorkspaceView.from(workspace);

        assertThat(view.workspacePath()).isEqualTo("/agent-workspace");
    }
}
