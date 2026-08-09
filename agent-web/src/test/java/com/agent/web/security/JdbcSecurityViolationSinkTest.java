package com.agent.web.security;

import com.agent.core.security.SecuritySeverity;
import com.agent.core.security.SecurityViolation;
import com.agent.core.security.SecurityViolationType;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcSecurityViolationSinkTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcClient jdbc;
    private JdbcSecurityViolationSink sink;

    @BeforeAll
    static void startPostgres() {
        boolean dockerAvailable;
        try {
            dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException exception) {
            Assumptions.assumeTrue(false, "Docker Engine 不可用: " + exception.getMessage());
            return;
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker Engine 不可用");
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @BeforeEach
    void setUp() {
        DataSource dataSource = dataSource();
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("truncate table agent_security_violations").update();
        sink = new JdbcSecurityViolationSink(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @Test
    void persistsOnlyStructuredSanitizedViolationFields() {
        UUID violationId = UUID.fromString("6d09ca04-4eb9-4cc1-8e91-a52f57d02b7a");
        UUID runId = UUID.fromString("b9c5a1e1-3f39-4f4a-9db2-6c1245f29b77");
        Instant occurredAt = Instant.parse("2026-08-09T12:00:00Z");
        sink.record(new SecurityViolation(
                violationId, runId, "user-1", "planner", Optional.empty(),
                SecurityViolationType.PROMPT_INJECTION, SecuritySeverity.HIGH,
                "prompt.test", "检测到外部内容影响 Agent 行为", occurredAt));

        assertThat(jdbc.sql("""
                select violation_id, run_id, user_id, node_name, tool_name,
                       violation_type, severity, rule_id, summary, occurred_at
                from agent_security_violations
                """).query((resultSet, rowNumber) -> new SecurityViolation(
                resultSet.getObject("violation_id", UUID.class),
                resultSet.getObject("run_id", UUID.class),
                resultSet.getString("user_id"),
                resultSet.getString("node_name"),
                Optional.ofNullable(resultSet.getString("tool_name")),
                SecurityViolationType.valueOf(resultSet.getString("violation_type")),
                SecuritySeverity.valueOf(resultSet.getString("severity")),
                resultSet.getString("rule_id"),
                resultSet.getString("summary"),
                resultSet.getTimestamp("occurred_at").toInstant())).single())
                .isEqualTo(new SecurityViolation(
                        violationId, runId, "user-1", "planner", Optional.empty(),
                        SecurityViolationType.PROMPT_INJECTION, SecuritySeverity.HIGH,
                        "prompt.test", "检测到外部内容影响 Agent 行为", occurredAt));
    }

    private DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(POSTGRES.getDriverClassName());
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}
