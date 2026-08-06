package com.agent.web.workspace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspacePermissionTest {

    @Test
    void permissionsAllowOnlyEqualOrHigherAuthority() {
        assertThat(WorkspacePermission.VIEWER.allows(WorkspacePermission.VIEWER)).isTrue();
        assertThat(WorkspacePermission.OPERATOR.allows(WorkspacePermission.VIEWER)).isTrue();
        assertThat(WorkspacePermission.OPERATOR.allows(WorkspacePermission.OWNER)).isFalse();
        assertThat(WorkspacePermission.OWNER.allows(WorkspacePermission.OPERATOR)).isTrue();
    }
}
