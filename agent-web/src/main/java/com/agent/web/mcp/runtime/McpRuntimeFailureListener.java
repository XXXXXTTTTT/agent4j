package com.agent.web.mcp.runtime;

import java.util.Objects;
import java.util.UUID;

/** MCP 容器运行时失败的异步通知端口。 */
@FunctionalInterface
public interface McpRuntimeFailureListener {

    void onFailure(Event event);

    record Event(
            UUID installationId,
            UUID snapshotId,
            String containerId,
            Reason reason,
            Throwable cause) {
        public Event {
            Objects.requireNonNull(installationId, "installationId 不能为空");
            Objects.requireNonNull(snapshotId, "snapshotId 不能为空");
            if (containerId == null || containerId.isBlank()) {
                throw new IllegalArgumentException("containerId 不能为空");
            }
            Objects.requireNonNull(reason, "reason 不能为空");
            Objects.requireNonNull(cause, "cause 不能为空");
        }
    }

    enum Reason {
        ATTACH_DISCONNECTED,
        CONTAINER_EXITED,
        STDOUT_FRAME_LIMIT_EXCEEDED,
        STDOUT_BUFFER_LIMIT_EXCEEDED,
        STDERR_LIMIT_EXCEEDED,
        STREAM_IO_FAILED
    }
}
