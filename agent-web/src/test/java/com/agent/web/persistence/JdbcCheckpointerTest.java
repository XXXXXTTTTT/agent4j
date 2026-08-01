package com.agent.web.persistence;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.CheckpointAppend;
import com.agent.core.engine.CheckpointConflictException;
import com.agent.core.engine.InterruptRequest;
import com.agent.core.engine.RunCheckpoint;
import com.agent.core.engine.RunNotFoundException;
import com.agent.core.engine.RunStatus;
import com.agent.core.llm.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcCheckpointerTest {

    private static final Instant NOW = Instant.parse("2026-08-01T08:00:00Z");
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcClient jdbcClient;
    private JdbcCheckpointer checkpointer;

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
    void setUpDatabase() {
        DataSource dataSource = dataSource();
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbcClient = JdbcClient.create(dataSource);
        jdbcClient.sql("truncate table agent_checkpoints, agent_runs").update();
        checkpointer = new JdbcCheckpointer(
                jdbcClient,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void migratesSchemaAndRoundTripsMultimodalAgentState() {
        assertThat(tableNames()).contains("agent_runs", "agent_checkpoints");
        AgentState state = new AgentState(
                List.of(ChatMessage.userMultimodal(List.of(
                        new ChatMessage.TextPart("检查页面"),
                        new ChatMessage.ImageUrlPart(new ChatMessage.ImageUrl(
                                "data:image/png;base64,AQID",
                                ChatMessage.ImageDetail.HIGH))))),
                Map.of("ops.stdout", "tests passed"),
                List.of("reviewer"));
        UUID runId = UUID.fromString("be7b1dd5-a09b-46dc-818b-a7cc2217197e");

        RunCheckpoint created = checkpointer.create(
                runId, "coder-ops-reviewer", state, "coder");

        assertThat(created).isEqualTo(new RunCheckpoint(
                runId,
                0,
                "coder-ops-reviewer",
                RunStatus.RUNNING,
                state,
                "coder",
                null,
                null,
                null,
                null,
                NOW));
        assertThat(checkpointer.loadLatest(runId)).contains(created);
        assertThat(jdbcClient.sql("select pg_typeof(state_json)::text from agent_checkpoints")
                .query(String.class)
                .single()).isEqualTo("jsonb");
    }

    @Test
    void appendsHistoryAndLoadsLatestRunsByExactStatus() {
        UUID waitingRunId = UUID.fromString("ac213392-f1e3-4de4-ae37-8c256b7b4adb");
        UUID completedRunId = UUID.fromString("557436af-09cf-4388-a285-0f5bc3778663");
        RunCheckpoint waitingCreated = checkpointer.create(
                waitingRunId, "approval", AgentState.empty(), "ops");
        RunCheckpoint completedCreated = checkpointer.create(
                completedRunId, "simple", AgentState.empty(), "done");
        InterruptRequest interrupt = new InterruptRequest(
                UUID.fromString("1053ab58-53a8-4a32-bbaa-92acaf2f8c72"),
                "ops",
                "需要审批",
                Map.of("command", "mvn verify"));

        RunCheckpoint waiting = checkpointer.append(new CheckpointAppend(
                waitingRunId,
                waitingCreated.version(),
                RunStatus.WAITING_APPROVAL,
                waitingCreated.state(),
                "ops",
                interrupt,
                null,
                null,
                null));
        RunCheckpoint completed = checkpointer.append(new CheckpointAppend(
                completedRunId,
                completedCreated.version(),
                RunStatus.COMPLETED,
                completedCreated.state().withTraceEntry("done"),
                null,
                null,
                null,
                null,
                null));

        assertThat(checkpointer.loadHistory(waitingRunId))
                .extracting(RunCheckpoint::version)
                .containsExactly(0L, 1L);
        assertThat(checkpointer.loadLatest(waitingRunId)).contains(waiting);
        assertThat(checkpointer.loadLatest(completedRunId)).contains(completed);
        assertThat(checkpointer.loadLatestByStatus(RunStatus.WAITING_APPROVAL))
                .extracting(RunCheckpoint::runId)
                .containsExactly(waitingRunId);
        assertThat(checkpointer.loadLatestByStatus(RunStatus.COMPLETED))
                .extracting(RunCheckpoint::runId)
                .containsExactly(completedRunId);
        assertThat(checkpointer.loadLatestByStatus(RunStatus.RUNNING)).isEmpty();
    }

    @Test
    void permitsOnlyOneConcurrentAppendForTheSameVersion() throws Exception {
        UUID runId = UUID.fromString("a26453e2-01e8-4f27-970b-d6f59e39af8e");
        RunCheckpoint created = checkpointer.create(
                runId, "simple", AgentState.empty(), "done");
        CheckpointAppend append = new CheckpointAppend(
                runId,
                created.version(),
                RunStatus.COMPLETED,
                created.state(),
                null,
                null,
                null,
                null,
                null);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Object>> futures = List.of(
                    executor.submit(() -> appendResult(append)),
                    executor.submit(() -> appendResult(append)));
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }

            assertThat(results).filteredOn(RunCheckpoint.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(CheckpointConflictException.class::isInstance)
                    .singleElement()
                    .satisfies(result -> assertThat(((CheckpointConflictException) result).runId())
                            .isEqualTo(runId));
            assertThat(checkpointer.loadHistory(runId)).hasSize(2);
        }
    }

    @Test
    void distinguishesMissingRunFromVersionConflict() {
        UUID missing = UUID.fromString("287108df-5095-4d7e-93aa-df9d231110ba");
        CheckpointAppend missingAppend = new CheckpointAppend(
                missing,
                0,
                RunStatus.COMPLETED,
                AgentState.empty(),
                null,
                null,
                null,
                null,
                null);
        assertThatThrownBy(() -> checkpointer.append(missingAppend))
                .isInstanceOfSatisfying(RunNotFoundException.class, exception ->
                        assertThat(exception.runId()).isEqualTo(missing));

        UUID existing = UUID.fromString("6e789f65-014b-49bf-9721-085843661278");
        checkpointer.create(existing, "simple", AgentState.empty(), "done");
        CheckpointAppend staleAppend = new CheckpointAppend(
                existing,
                1,
                RunStatus.COMPLETED,
                AgentState.empty(),
                null,
                null,
                null,
                null,
                null);
        assertThatThrownBy(() -> checkpointer.append(staleAppend))
                .isInstanceOfSatisfying(CheckpointConflictException.class, exception -> {
                    assertThat(exception.runId()).isEqualTo(existing);
                    assertThat(exception.expectedVersion()).isEqualTo(1);
                });
    }

    @Test
    void rollsBackRunVersionWhenCheckpointInsertFails() {
        UUID runId = UUID.fromString("9c3475bb-2520-40f5-a71f-b79e91cad57f");
        RunCheckpoint created = checkpointer.create(
                runId, "simple", AgentState.empty(), "done");
        jdbcClient.sql("""
                alter table agent_checkpoints
                add constraint test_reject_version_one check (version <> 1)
                """).update();
        try {
            CheckpointAppend append = new CheckpointAppend(
                    runId,
                    created.version(),
                    RunStatus.COMPLETED,
                    created.state(),
                    null,
                    null,
                    null,
                    null,
                    null);

            assertThatThrownBy(() -> checkpointer.append(append))
                    .isInstanceOf(RuntimeException.class);
            assertThat(jdbcClient.sql("""
                    select latest_version from agent_runs where run_id = :runId
                    """).param("runId", runId).query(Long.class).single()).isZero();
            assertThat(checkpointer.loadHistory(runId)).containsExactly(created);
        } finally {
            jdbcClient.sql("""
                    alter table agent_checkpoints drop constraint test_reject_version_one
                    """).update();
        }
    }

    private Object appendResult(CheckpointAppend append) {
        try {
            return checkpointer.append(append);
        } catch (CheckpointConflictException exception) {
            return exception;
        }
    }

    private List<String> tableNames() {
        return jdbcClient.sql("""
                select table_name
                from information_schema.tables
                where table_schema = 'public'
                  and table_name in ('agent_runs', 'agent_checkpoints')
                order by table_name
                """).query(String.class).list();
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
