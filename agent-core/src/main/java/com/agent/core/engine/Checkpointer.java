package com.agent.core.engine;

import java.util.List;
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
}
