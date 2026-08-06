package com.agent.web.conversation;

import com.agent.web.identity.Actor;
import com.agent.web.workspace.WorkspacePermission;
import com.agent.web.workspace.WorkspaceRecord;
import com.agent.web.workspace.WorkspaceRepository;
import com.agent.web.persistence.JdbcConversationRepository;
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
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcConversationRepositoryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-07T00:00:00Z");
    private static final UUID WORKSPACE_ID =
            UUID.fromString("9a1e9db3-3df7-40e8-87b3-9a42e69ec1a1");
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcClient jdbc;
    private JdbcConversationRepository repository;

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
        jdbc.sql("truncate table agent_conversation_turns, agent_conversations, "
                + "agent_workspace_members, agent_workspaces, agent_users cascade").update();
        repository = new JdbcConversationRepository(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void bootstrapsDefaultWorkspaceIdempotentlyAndIsolatesMembers() {
        Actor owner = new Actor("owner", "Owner");
        WorkspaceRecord first = repository.ensureDefaultWorkspace(
                WORKSPACE_ID,
                owner,
                "项目工作区",
                Path.of("D:/agent4j"),
                "repo-owner",
                NOW);
        WorkspaceRecord second = repository.ensureDefaultWorkspace(
                UUID.randomUUID(),
                owner,
                "项目工作区",
                Path.of("D:/agent4j"),
                "repo-owner",
                NOW);

        assertThat(first.workspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(second.workspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(repository.findWorkspaces("owner"))
                .extracting(WorkspaceRecord::permission)
                .containsExactly(WorkspacePermission.OWNER);

        Actor viewer = new Actor("viewer", "Viewer");
        repository.ensureUser(viewer, NOW);
        repository.grantMember(WORKSPACE_ID, viewer.userId(), WorkspacePermission.VIEWER, NOW);
        assertThat(repository.findWorkspace(WORKSPACE_ID, "viewer"))
                .get()
                .extracting(WorkspaceRecord::permission)
                .isEqualTo(WorkspacePermission.VIEWER);
        assertThat(repository.findWorkspace(WORKSPACE_ID, "missing")).isEmpty();
    }

    @Test
    void disabledUsersCannotReadWorkspace() {
        Actor owner = new Actor("owner-disabled", "Owner");
        repository.ensureDefaultWorkspace(
                WORKSPACE_ID,
                owner,
                "项目工作区",
                Path.of("D:/agent4j"),
                "repo-owner",
                NOW);
        jdbc.sql("update agent_users set enabled = false where user_id = :userId")
                .param("userId", owner.userId()).update();

        assertThat(repository.findWorkspace(WORKSPACE_ID, owner.userId())).isEmpty();
    }

    @Test
    void persistsConversationTurnsWithStableIndexesAndIdempotentTerminalUpdates() {
        Actor owner = new Actor("conversation-owner", "Owner");
        repository.ensureDefaultWorkspace(
                WORKSPACE_ID, owner, "项目工作区", Path.of("D:/agent4j"), "repo-owner", NOW);
        UUID conversationId = UUID.randomUUID();
        ConversationRecord conversation = repository.createConversation(
                conversationId, WORKSPACE_ID, owner, "首轮标题", NOW);

        ConversationTurnRecord first = repository.createPendingTurn(
                conversationId, owner.userId(), "第一轮", NOW);
        UUID runId = UUID.randomUUID();
        jdbc.sql("""
                insert into agent_runs (
                    run_id, graph_id, status, latest_version, created_at, updated_at
                ) values (:runId, 'code-agent', 'RUNNING', 0, :createdAt, :updatedAt)
                """)
                .param("runId", runId)
                .param("createdAt", java.sql.Timestamp.from(NOW))
                .param("updatedAt", java.sql.Timestamp.from(NOW))
                .update();
        ConversationTurnRecord running = repository.markTurnRunning(first.turnId(), runId, NOW);
        ConversationTurnRecord completed = repository.markTurnCompleted(
                running.turnId(), "第一轮回答", NOW);
        ConversationTurnRecord completedAgain = repository.markTurnCompleted(
                completed.turnId(), "第一轮回答", NOW);
        ConversationTurnRecord second = repository.createPendingTurn(
                conversationId, owner.userId(), "第二轮", NOW);

        assertThat(conversation.status()).isEqualTo(ConversationStatus.ACTIVE);
        assertThat(first.turnIndex()).isEqualTo(1);
        assertThat(completed.status()).isEqualTo(ConversationTurnStatus.COMPLETED);
        assertThat(completedAgain).isEqualTo(completed);
        assertThat(second.turnIndex()).isEqualTo(2);
        assertThat(repository.findTurns(conversationId, owner.userId()))
                .extracting(ConversationTurnRecord::turnIndex)
                .containsExactly(1L, 2L);
    }

    @Test
    void rejectsConcurrentActiveTurnAndArchivedConversation() {
        Actor owner = new Actor("conversation-conflict-owner", "Owner");
        repository.ensureDefaultWorkspace(
                WORKSPACE_ID, owner, "项目工作区", Path.of("D:/agent4j"), "repo-owner", NOW);
        UUID conversationId = UUID.randomUUID();
        repository.createConversation(conversationId, WORKSPACE_ID, owner, "标题", NOW);
        repository.createPendingTurn(conversationId, owner.userId(), "执行中", NOW);

        assertThatThrownBy(() -> repository.createPendingTurn(
                conversationId, owner.userId(), "并发输入", NOW))
                .isInstanceOf(JdbcConversationRepository.ConversationConflictException.class);

        repository.archiveConversation(conversationId, owner.userId(), NOW);
        assertThatThrownBy(() -> repository.createPendingTurn(
                conversationId, owner.userId(), "归档后输入", NOW))
                .isInstanceOf(JdbcConversationRepository.ConversationConflictException.class);
    }

    @Test
    void titleHelperUsesFirstEightyUnicodeCodePointsAfterWhitespaceCollapse() {
        String title = JdbcConversationRepository.deriveTitle("  你好\t世界  "+ "a".repeat(100));

        assertThat(title).startsWith("你好 世界");
        assertThat(title.codePointCount(0, title.length())).isEqualTo(80);
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
