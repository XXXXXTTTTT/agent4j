package com.agent.web.persistence;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.ApprovalDecision;
import com.agent.core.engine.CheckpointAppend;
import com.agent.core.engine.CheckpointConflictException;
import com.agent.core.engine.Checkpointer;
import com.agent.core.engine.InterruptRequest;
import com.agent.core.engine.RunCheckpoint;
import com.agent.core.engine.RunNotFoundException;
import com.agent.core.engine.RunStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 使用 PostgreSQL 事务实现追加式 Run Checkpoint。 */
public final class JdbcCheckpointer implements Checkpointer {

    private static final String CHECKPOINT_COLUMNS = """
            checkpoint.run_id,
            checkpoint.version,
            checkpoint.graph_id,
            checkpoint.status,
            checkpoint.state_json,
            checkpoint.next_node,
            checkpoint.interrupt_json,
            checkpoint.approval_decision,
            checkpoint.approval_reason,
            checkpoint.error,
            checkpoint.created_at
            """;

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final RowMapper<RunCheckpoint> checkpointRowMapper = this::mapCheckpoint;

    /**
     * 创建 PostgreSQL Checkpointer。
     *
     * @param jdbcClient         JDBC 客户端
     * @param transactionTemplate 事务模板
     * @param objectMapper       状态 JSON 映射器
     * @param clock              快照时钟
     */
    public JdbcCheckpointer(
            JdbcClient jdbcClient,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            Clock clock) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient 不能为空");
        this.transactionTemplate = Objects.requireNonNull(
                transactionTemplate, "transactionTemplate 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /** 在一个事务中创建 Run 元数据与版本 0 快照。 */
    @Override
    public RunCheckpoint create(
            UUID runId,
            String graphId,
            AgentState initialState,
            String entryNode) {
        Instant createdAt = clock.instant();
        RunCheckpoint checkpoint = new RunCheckpoint(
                runId,
                0,
                graphId,
                RunStatus.RUNNING,
                initialState,
                entryNode,
                null,
                null,
                null,
                null,
                createdAt);
        return requireTransactionResult(transactionTemplate.execute(status -> {
            jdbcClient.sql("""
                    insert into agent_runs (
                        run_id, graph_id, status, latest_version, created_at, updated_at
                    ) values (
                        :runId, :graphId, :status, :latestVersion, :createdAt, :updatedAt
                    )
                    """)
                    .param("runId", runId)
                    .param("graphId", graphId)
                    .param("status", RunStatus.RUNNING.name())
                    .param("latestVersion", 0L)
                    .param("createdAt", Timestamp.from(createdAt))
                    .param("updatedAt", Timestamp.from(createdAt))
                    .update();
            insertCheckpoint(checkpoint);
            return checkpoint;
        }));
    }

    /** 使用 Run 元数据行的版本执行乐观锁追加。 */
    @Override
    public RunCheckpoint append(CheckpointAppend append) {
        Objects.requireNonNull(append, "append 不能为空");
        Instant createdAt = clock.instant();
        return requireTransactionResult(transactionTemplate.execute(status -> {
            int updated = jdbcClient.sql("""
                    update agent_runs
                    set latest_version = latest_version + 1,
                        status = :status,
                        updated_at = :updatedAt
                    where run_id = :runId
                      and latest_version = :expectedVersion
                    """)
                    .param("status", append.status().name())
                    .param("updatedAt", Timestamp.from(createdAt))
                    .param("runId", append.runId())
                    .param("expectedVersion", append.expectedVersion())
                    .update();
            if (updated != 1) {
                throwAppendConflict(append.runId(), append.expectedVersion());
            }
            String graphId = jdbcClient.sql("""
                    select graph_id from agent_runs where run_id = :runId
                    """)
                    .param("runId", append.runId())
                    .query(String.class)
                    .single();
            RunCheckpoint checkpoint = new RunCheckpoint(
                    append.runId(),
                    append.expectedVersion() + 1,
                    graphId,
                    append.status(),
                    append.state(),
                    append.nextNode(),
                    append.interruptRequest(),
                    append.approvalDecision(),
                    append.approvalReason(),
                    append.error(),
                    createdAt);
            insertCheckpoint(checkpoint);
            return checkpoint;
        }));
    }

    /** 读取 Run 最新版本。 */
    @Override
    public Optional<RunCheckpoint> loadLatest(UUID runId) {
        Objects.requireNonNull(runId, "runId 不能为空");
        return jdbcClient.sql("""
                select %s
                from agent_runs run
                join agent_checkpoints checkpoint
                  on checkpoint.run_id = run.run_id
                 and checkpoint.version = run.latest_version
                where run.run_id = :runId
                """.formatted(CHECKPOINT_COLUMNS))
                .param("runId", runId)
                .query(checkpointRowMapper)
                .optional();
    }

    /** 按版本升序读取 Run 历史。 */
    @Override
    public List<RunCheckpoint> loadHistory(UUID runId) {
        Objects.requireNonNull(runId, "runId 不能为空");
        return List.copyOf(jdbcClient.sql("""
                select %s
                from agent_checkpoints checkpoint
                where checkpoint.run_id = :runId
                order by checkpoint.version
                """.formatted(CHECKPOINT_COLUMNS))
                .param("runId", runId)
                .query(checkpointRowMapper)
                .list());
    }

    /** 按 Run 元数据的精确最新状态查询。 */
    @Override
    public List<RunCheckpoint> loadLatestByStatus(RunStatus status) {
        Objects.requireNonNull(status, "status 不能为空");
        return List.copyOf(jdbcClient.sql("""
                select %s
                from agent_runs run
                join agent_checkpoints checkpoint
                  on checkpoint.run_id = run.run_id
                 and checkpoint.version = run.latest_version
                where run.status = :status
                order by run.updated_at, run.run_id
                """.formatted(CHECKPOINT_COLUMNS))
                .param("status", status.name())
                .query(checkpointRowMapper)
                .list());
    }

    private void insertCheckpoint(RunCheckpoint checkpoint) {
        String stateJson = writeJson(checkpoint.state());
        String interruptJson = checkpoint.interruptRequest() == null
                ? null
                : writeJson(checkpoint.interruptRequest());
        String approvalDecision = checkpoint.approvalDecision() == null
                ? null
                : checkpoint.approvalDecision().name();
        jdbcClient.sql("""
                insert into agent_checkpoints (
                    run_id,
                    version,
                    graph_id,
                    status,
                    state_json,
                    next_node,
                    interrupt_json,
                    approval_decision,
                    approval_reason,
                    error,
                    created_at
                ) values (
                    :runId,
                    :version,
                    :graphId,
                    :status,
                    cast(:stateJson as jsonb),
                    :nextNode,
                    cast(:interruptJson as jsonb),
                    :approvalDecision,
                    :approvalReason,
                    :error,
                    :createdAt
                )
                """)
                .param("runId", checkpoint.runId())
                .param("version", checkpoint.version())
                .param("graphId", checkpoint.graphId())
                .param("status", checkpoint.status().name())
                .param("stateJson", stateJson)
                .param("nextNode", checkpoint.nextNode(), Types.VARCHAR)
                .param("interruptJson", interruptJson, Types.VARCHAR)
                .param("approvalDecision", approvalDecision, Types.VARCHAR)
                .param("approvalReason", checkpoint.approvalReason(), Types.VARCHAR)
                .param("error", checkpoint.error(), Types.VARCHAR)
                .param("createdAt", Timestamp.from(checkpoint.createdAt()))
                .update();
    }

    private void throwAppendConflict(UUID runId, long expectedVersion) {
        long count = jdbcClient.sql("""
                select count(*) from agent_runs where run_id = :runId
                """)
                .param("runId", runId)
                .query(Long.class)
                .single();
        if (count == 0) {
            throw new RunNotFoundException(runId);
        }
        throw new CheckpointConflictException(runId, expectedVersion);
    }

    private RunCheckpoint mapCheckpoint(ResultSet resultSet, int rowNumber)
            throws SQLException {
        String interruptJson = resultSet.getString("interrupt_json");
        String approvalDecision = resultSet.getString("approval_decision");
        return new RunCheckpoint(
                resultSet.getObject("run_id", UUID.class),
                resultSet.getLong("version"),
                resultSet.getString("graph_id"),
                RunStatus.valueOf(resultSet.getString("status")),
                readJson(resultSet.getString("state_json"), AgentState.class),
                resultSet.getString("next_node"),
                interruptJson == null
                        ? null
                        : readJson(interruptJson, InterruptRequest.class),
                approvalDecision == null
                        ? null
                        : ApprovalDecision.valueOf(approvalDecision),
                resultSet.getString("approval_reason"),
                resultSet.getString("error"),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Checkpoint JSON 序列化失败", exception);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Checkpoint JSON 反序列化失败", exception);
        }
    }

    private RunCheckpoint requireTransactionResult(RunCheckpoint result) {
        return Objects.requireNonNull(result, "事务返回 Checkpoint 不能为空");
    }
}
