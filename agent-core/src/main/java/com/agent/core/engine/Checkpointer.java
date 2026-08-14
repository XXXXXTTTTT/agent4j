package com.agent.core.engine;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Run Checkpoint 的权威持久化端口。 */
public interface Checkpointer {

    /** 创建版本 0 快照。 */
    RunCheckpoint create(UUID runId, String graphId, AgentState initialState, String entryNode);

    /** 以乐观锁追加下一版本快照。 */
    RunCheckpoint append(CheckpointAppend append);

    /** 读取 Run 最新快照。 */
    Optional<RunCheckpoint> loadLatest(UUID runId);

    /** 按版本升序读取 Run 全部快照。 */
    List<RunCheckpoint> loadHistory(UUID runId);

    /** 读取最新状态等于指定值的 Run 快照。 */
    List<RunCheckpoint> loadLatestByStatus(RunStatus status);

    /** 将权威最新状态恢复到同一 Run 的精确历史版本。 */
    default RunCheckpoint restore(UUID runId, long version) {
        if (version < 0) {
            throw new IllegalArgumentException("version 不能小于 0");
        }
        return loadHistory(Objects.requireNonNull(runId, "runId 不能为空")).stream()
                .filter(checkpoint -> checkpoint.version() == version)
                .findFirst()
                .orElseThrow(() -> new RunNotFoundException(runId));
    }
}
