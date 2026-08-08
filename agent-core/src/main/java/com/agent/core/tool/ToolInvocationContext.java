package com.agent.core.tool;

import com.agent.core.intent.RequiredCapability;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 工具调用所属用户、Run、节点与工作区上下文。 */
public record ToolInvocationContext(
        UUID runId,
        String nodeName,
        String userId,
        Path workspaceRoot,
        Set<RequiredCapability> grantedCapabilities,
        boolean approvalGranted) {

    /** 校验上下文并规范化工作区路径。 */
    public ToolInvocationContext {
        Objects.requireNonNull(runId, "runId 不能为空");
        if (nodeName == null || nodeName.isBlank()) {
            throw new IllegalArgumentException("nodeName 不能为空");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot 不能为空")
                .toAbsolutePath()
                .normalize();
        grantedCapabilities = Set.copyOf(Objects.requireNonNull(
                grantedCapabilities, "grantedCapabilities 不能为空"));
    }
}
