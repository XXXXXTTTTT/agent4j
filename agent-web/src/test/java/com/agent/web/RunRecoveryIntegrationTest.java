package com.agent.web;

import com.agent.core.engine.AgentRunService;
import com.agent.core.engine.AgentState;
import com.agent.core.engine.ApprovalCommand;
import com.agent.core.engine.ApprovalDecision;
import com.agent.core.engine.CheckpointAppend;
import com.agent.core.engine.GraphRegistry;
import com.agent.core.engine.InterruptPolicy;
import com.agent.core.engine.InterruptRequest;
import com.agent.core.engine.RunCheckpoint;
import com.agent.core.engine.RunStatus;
import com.agent.core.engine.StateGraph;
import com.agent.core.trace.TraceEvent;
import com.agent.web.persistence.JdbcCheckpointer;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RunRecoveryIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final UUID FIRST_INTERRUPT_ID =
            UUID.fromString("2a0e35fb-c93e-4b26-a564-e830dbb14c36");
    private static final UUID SECOND_INTERRUPT_ID =
            UUID.fromString("6e7b4206-e005-49d2-80f1-de3d1afda822");

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
        jdbcClient.sql("""
                truncate table agent_conversation_turns, agent_checkpoints, agent_runs
                """).update();
        checkpointer = new JdbcCheckpointer(
                jdbcClient,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                new ObjectMapper().findAndRegisterModules(),
                Clock.systemUTC());
    }

    @Test
    void resumesOrdinaryRunningCheckpointFromExactNextNodeOnce() throws Exception {
        UUID runId = UUID.fromString("169766c4-972b-4386-b8ad-d0090b9f71a4");
        RunCheckpoint created = checkpointer.create(
                runId, "ordinary-recovery", AgentState.empty(), "first");
        checkpointer.append(new CheckpointAppend(
                runId,
                created.version(),
                RunStatus.RUNNING,
                created.state().withTraceEntry("first"),
                "second",
                null,
                null,
                null,
                null));
        AtomicInteger firstExecutions = new AtomicInteger();
        AtomicInteger secondExecutions = new AtomicInteger();
        CountDownLatch completedEvent = new CountDownLatch(1);
        GraphRegistry registry = new GraphRegistry(Map.of(
                "ordinary-recovery",
                () -> new StateGraph(2)
                        .addNode("first", state -> {
                            firstExecutions.incrementAndGet();
                            return state.withTraceEntry("first");
                        })
                        .addNode("second", state -> {
                            secondExecutions.incrementAndGet();
                            return state.withTraceEntry("second");
                        })
                        .setEntryPoint("first")
                        .addEdge("first", "second")
                        .addEdge("second", StateGraph.END)));

        try (AgentRunService service = new AgentRunService(
                checkpointer,
                registry,
                event -> countCompleted(event, runId, completedEvent))) {
            service.recoverRunningRuns();

            assertThat(completedEvent.await(5, TimeUnit.SECONDS)).isTrue();
            RunCheckpoint completed = checkpointer.loadLatest(runId).orElseThrow();
            assertThat(completed.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(completed.state().trace()).containsExactly("first", "second");
            assertThat(firstExecutions).hasValue(0);
            assertThat(secondExecutions).hasValue(1);

            service.recoverRunningRuns();
            List<RunCheckpoint> history = checkpointer.loadHistory(runId);
            assertThat(history).extracting(RunCheckpoint::version)
                    .containsExactly(0L, 1L, 2L);
            assertThat(history).filteredOn(checkpoint ->
                    checkpoint.status() == RunStatus.COMPLETED).hasSize(1);
        }
    }

    @Test
    void bypassesOnlyApprovedStartNodeAndInterruptsNextGuardedNode() throws Exception {
        UUID runId = UUID.fromString("0d73111d-d0bb-4833-8f0b-dac7b69a40cb");
        RunCheckpoint created = checkpointer.create(
                runId, "approved-recovery", AgentState.empty(), "ops-first");
        RunCheckpoint firstWaiting = checkpointer.append(new CheckpointAppend(
                runId,
                created.version(),
                RunStatus.WAITING_APPROVAL,
                created.state(),
                "ops-first",
                interrupt("ops-first", FIRST_INTERRUPT_ID),
                null,
                null,
                null));
        checkpointer.append(new CheckpointAppend(
                runId,
                firstWaiting.version(),
                RunStatus.RUNNING,
                firstWaiting.state(),
                "ops-first",
                null,
                ApprovalDecision.APPROVE,
                "批准第一个节点",
                null));
        AtomicInteger firstExecutions = new AtomicInteger();
        AtomicInteger secondExecutions = new AtomicInteger();
        CountDownLatch secondInterrupted = new CountDownLatch(1);
        CountDownLatch completedEvent = new CountDownLatch(1);
        GraphRegistry registry = approvedRecoveryRegistry(firstExecutions, secondExecutions);

        try (AgentRunService service = new AgentRunService(
                checkpointer,
                registry,
                event -> countRecoveryEvents(
                        event, runId, secondInterrupted, completedEvent))) {
            service.recoverRunningRuns();

            assertThat(secondInterrupted.await(5, TimeUnit.SECONDS)).isTrue();
            RunCheckpoint secondWaiting = checkpointer.loadLatest(runId).orElseThrow();
            assertThat(secondWaiting.status()).isEqualTo(RunStatus.WAITING_APPROVAL);
            assertThat(secondWaiting.nextNode()).isEqualTo("ops-second");
            assertThat(secondWaiting.interruptRequest())
                    .isEqualTo(interrupt("ops-second", SECOND_INTERRUPT_ID));
            assertThat(secondWaiting.state().trace()).containsExactly("ops-first");
            assertThat(firstExecutions).hasValue(1);
            assertThat(secondExecutions).hasValue(0);

            service.decide(
                    runId,
                    new ApprovalCommand(
                            ApprovalDecision.APPROVE,
                            secondWaiting.version(),
                            "批准第二个节点"));
            assertThat(completedEvent.await(5, TimeUnit.SECONDS)).isTrue();
            RunCheckpoint completed = checkpointer.loadLatest(runId).orElseThrow();
            assertThat(completed.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(completed.state().trace())
                    .containsExactly("ops-first", "ops-second");
            assertThat(secondExecutions).hasValue(1);

            service.recoverRunningRuns();
            List<RunCheckpoint> history = checkpointer.loadHistory(runId);
            assertThat(history).extracting(RunCheckpoint::version)
                    .containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L);
            assertThat(history).filteredOn(checkpoint ->
                    checkpoint.status() == RunStatus.COMPLETED).hasSize(1);
        }
    }

    private GraphRegistry approvedRecoveryRegistry(
            AtomicInteger firstExecutions,
            AtomicInteger secondExecutions) {
        return new GraphRegistry(Map.of("approved-recovery", () -> {
            InterruptPolicy policy = (runId, nodeName, state) -> switch (nodeName) {
                case "ops-first" -> Optional.of(interrupt("ops-first", FIRST_INTERRUPT_ID));
                case "ops-second" -> Optional.of(interrupt("ops-second", SECOND_INTERRUPT_ID));
                default -> Optional.empty();
            };
            return new StateGraph(2, policy)
                    .addNode("ops-first", state -> {
                        firstExecutions.incrementAndGet();
                        return state.withTraceEntry("ops-first");
                    })
                    .addNode("ops-second", state -> {
                        secondExecutions.incrementAndGet();
                        return state.withTraceEntry("ops-second");
                    })
                    .setEntryPoint("ops-first")
                    .addEdge("ops-first", "ops-second")
                    .addEdge("ops-second", StateGraph.END);
        }));
    }

    private InterruptRequest interrupt(String nodeName, UUID interruptId) {
        return new InterruptRequest(
                interruptId,
                nodeName,
                "危险操作需要审批",
                Map.of("command", nodeName));
    }

    private void countCompleted(
            TraceEvent event,
            UUID runId,
            CountDownLatch completedEvent) {
        if (event.runId().equals(runId) && event instanceof TraceEvent.Completed) {
            completedEvent.countDown();
        }
    }

    private void countRecoveryEvents(
            TraceEvent event,
            UUID runId,
            CountDownLatch secondInterrupted,
            CountDownLatch completedEvent) {
        if (!event.runId().equals(runId)) {
            return;
        }
        if (event instanceof TraceEvent.Interrupted interrupted
                && "ops-second".equals(interrupted.nodeName())) {
            secondInterrupted.countDown();
        }
        if (event instanceof TraceEvent.Completed) {
            completedEvent.countDown();
        }
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
