package com.agent.web.security;

import com.agent.core.security.SecurityViolation;
import com.agent.core.security.SecurityViolationSink;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** 使用 PostgreSQL 保存已经脱敏的安全违规事件。 */
public final class JdbcSecurityViolationSink implements SecurityViolationSink {

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;

    /** 创建安全违规 JDBC Sink。事务边界由调用方注入。 */
    public JdbcSecurityViolationSink(
            JdbcClient jdbcClient,
            TransactionTemplate transactionTemplate) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient 不能为空");
        this.transactionTemplate = Objects.requireNonNull(
                transactionTemplate, "transactionTemplate 不能为空");
    }

    /** 在事务中写入固定字段，不接受任何输入原文。 */
    @Override
    public void record(SecurityViolation violation) {
        Objects.requireNonNull(violation, "violation 不能为空");
        try {
            transactionTemplate.executeWithoutResult(status -> jdbcClient.sql("""
                    insert into agent_security_violations (
                        violation_id, run_id, user_id, node_name, tool_name,
                        violation_type, severity, rule_id, summary, occurred_at
                    ) values (
                        :violationId, :runId, :userId, :nodeName, :toolName,
                        :violationType, :severity, :ruleId, :summary, :occurredAt
                    )
                    """)
                    .param("violationId", violation.violationId())
                    .param("runId", violation.runId())
                    .param("userId", violation.userId())
                    .param("nodeName", violation.nodeName())
                    .param("toolName", violation.toolName().orElse(null))
                    .param("violationType", violation.type().name())
                    .param("severity", violation.severity().name())
                    .param("ruleId", violation.ruleId())
                    .param("summary", violation.summary())
                    .param("occurredAt", Timestamp.from(
                            violation.occurredAt().truncatedTo(ChronoUnit.MICROS)))
                    .update());
        } catch (RuntimeException exception) {
            throw new SecurityPersistenceException("安全违规持久化失败", exception);
        }
    }

    /** 安全持久化失败，不得被调用方误判为写入成功。 */
    public static final class SecurityPersistenceException extends RuntimeException {
        public SecurityPersistenceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
