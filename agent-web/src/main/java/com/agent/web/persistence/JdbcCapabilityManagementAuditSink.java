package com.agent.web.persistence;

import com.agent.web.capability.CapabilityManagementAuditEvent;
import com.agent.web.capability.CapabilityManagementAuditSink;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** 使用 PostgreSQL 保存能力目录与安装管理审计事件。 */
public final class JdbcCapabilityManagementAuditSink implements CapabilityManagementAuditSink {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Supplier<UUID> uuidSupplier;

    public JdbcCapabilityManagementAuditSink(
            JdbcClient jdbc, TransactionTemplate transactions, Supplier<UUID> uuidSupplier) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不能为空");
        this.transactions = Objects.requireNonNull(transactions, "transactions 不能为空");
        this.uuidSupplier = Objects.requireNonNull(uuidSupplier, "uuidSupplier 不能为空");
    }

    @Override
    public void record(CapabilityManagementAuditEvent event) {
        Objects.requireNonNull(event, "event 不能为空");
        try {
            transactions.executeWithoutResult(status -> jdbc.sql("""
                    insert into agent_capability_management_audit (
                        audit_id, event_type, actor_user_id, workspace_id, installation_id,
                        skill_id, run_id, source_commit_sha, result, occurred_at
                    ) values (
                        :auditId, :eventType, :actorUserId, :workspaceId, :installationId,
                        :skillId, :runId, :sourceCommitSha, :result, :occurredAt
                    )
                    """)
                    .param("auditId", uuidSupplier.get())
                    .param("eventType", event.eventType())
                    .param("actorUserId", event.actorUserId())
                    .param("workspaceId", event.workspaceId())
                    .param("installationId", event.installationId())
                    .param("skillId", event.skillId())
                    .param("runId", event.runId())
                    .param("sourceCommitSha", event.sourceCommitSha())
                    .param("result", event.result())
                    .param("occurredAt", Timestamp.from(event.occurredAt().truncatedTo(ChronoUnit.MICROS)))
                    .update());
        } catch (RuntimeException exception) {
            throw new AuditPersistenceException("能力管理审计持久化失败", exception);
        }
    }

    public static final class AuditPersistenceException extends RuntimeException {
        public AuditPersistenceException(String message, Throwable cause) { super(message, cause); }
    }
}
