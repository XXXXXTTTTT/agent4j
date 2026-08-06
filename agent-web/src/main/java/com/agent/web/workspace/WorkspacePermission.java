package com.agent.web.workspace;

/** 工作区成员权限，数值越大可执行的操作越多。 */
public enum WorkspacePermission {
    VIEWER(1),
    OPERATOR(2),
    OWNER(3);

    private final int authority;

    WorkspacePermission(int authority) {
        this.authority = authority;
    }

    /** 判断当前权限是否满足所需权限。 */
    public boolean allows(WorkspacePermission required) {
        return authority >= required.authority;
    }
}
