package com.agent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class Agent4jCliTest {

    private static final UUID WORKSPACE_ID = UUID.fromString(
            "deba7286-c149-4d73-83fd-6bc91326ac76");
    private static final UUID FIRST_CONVERSATION_ID = UUID.fromString(
            "2ee9ea03-6340-4c2a-a510-eb6a7b13def3");
    private static final UUID SECOND_CONVERSATION_ID = UUID.fromString(
            "37dc58ec-a824-45b8-bffc-9d0fd85a253f");
    private static final UUID NEW_CONVERSATION_ID = UUID.fromString(
            "7fbef74c-b6a8-4de2-9cbd-b123291a00a4");
    private static final UUID RUN_ID = UUID.fromString(
            "cdf4b51e-46fc-4dbd-aa82-06bd555e4226");

    @TempDir
    Path workspace;

    @Test
    void restoresConversationAndExecutesInteractiveCommands() {
        FakeClient client = new FakeClient(true);
        String input = String.join("\n",
                "/sessions",
                "/use " + SECOND_CONVERSATION_ID,
                "/status",
                "/new",
                "解释当前架构",
                "/exit",
                "退出后不应提交") + "\n";
        List<String> output = new ArrayList<>();
        Agent4jCli cli = new Agent4jCli(
                client, new StringReader(input), output::add, () -> { });

        int exitCode = cli.run(workspace);

        assertThat(exitCode).isZero();
        assertThat(client.identityCalls).isEqualTo(1);
        assertThat(client.workspaceCreates).isEmpty();
        assertThat(client.createdConversationWorkspaceIds).containsExactly(WORKSPACE_ID);
        assertThat(client.submittedConversationIds).containsExactly(NEW_CONVERSATION_ID);
        assertThat(client.submittedContents).containsExactly("解释当前架构");
        assertThat(client.followedTraceRunIds).containsExactly(RUN_ID);
        assertThat(client.followedLogRunIds).containsExactly(RUN_ID);
        assertThat(output).anyMatch(line -> line.contains("Local User (local)"));
        assertThat(output).anyMatch(line -> line.contains("Agent4J [/agent-workspace] OWNER"));
        assertThat(output).anyMatch(line -> line.contains(FIRST_CONVERSATION_ID.toString()));
        assertThat(output).anyMatch(line -> line.contains(SECOND_CONVERSATION_ID.toString()));
        assertThat(output).anyMatch(line -> line.contains("status=COMPLETED"));
        assertThat(output).anyMatch(line -> line.contains("[planner] 正在规划"));
        assertThat(output).anyMatch(line -> line.contains("编译中"));
        assertThat(output).anyMatch(line -> line.contains("最终回答"));
        assertThat(client.submittedContents).doesNotContain("退出后不应提交");
    }

    @Test
    void createsExactServerWorkspaceAndFirstConversationWhenMissing() {
        FakeClient client = new FakeClient(false);
        List<String> output = new ArrayList<>();
        Agent4jCli cli = new Agent4jCli(
                client, new StringReader("/exit\n"), output::add, () -> { });

        int exitCode = cli.run(workspace);

        assertThat(exitCode).isZero();
        assertThat(client.workspaceCreates).containsExactly(
                new WorkspaceCreate("Agent4J", "/agent-workspace", "local"));
        assertThat(client.createdConversationWorkspaceIds).containsExactly(WORKSPACE_ID);
        assertThat(output).anyMatch(line -> line.contains(NEW_CONVERSATION_ID.toString()));
    }

    @Test
    void redactsSecretsFromTraceLogsAndFinalAnswer() {
        FakeClient client = new FakeClient(true);
        client.traceData = """
                {"kind":"EVENT","event":{"type":"NODE_PROGRESS",\
                "nodeName":"planner","summary":"Authorization: Bearer top-secret"}}
                """;
        client.logData = """
                {"kind":"LOG","event":{"nodeName":"ops",\
                "stream":"STDOUT","text":"AGENT_LLM_API_KEY=abc123"}}
                """;
        client.finalAnswer = "password=hunter2，配置来自 .env";
        List<String> output = new ArrayList<>();
        Agent4jCli cli = new Agent4jCli(
                client,
                new StringReader("/new\n检查配置\n/exit\n"),
                output::add,
                () -> { });

        cli.run(workspace);

        String rendered = String.join("\n", output);
        assertThat(rendered)
                .doesNotContain("top-secret")
                .doesNotContain("abc123")
                .doesNotContain("hunter2")
                .doesNotContain(".env")
                .contains("[REDACTED]");
    }

    private static final class FakeClient implements Agent4jClient {

        private final List<Workspace> workspaces = new ArrayList<>();
        private final List<Conversation> conversations = new ArrayList<>();
        private final Map<UUID, Integer> turnReads = new HashMap<>();
        private final List<WorkspaceCreate> workspaceCreates = new ArrayList<>();
        private final List<UUID> createdConversationWorkspaceIds = new ArrayList<>();
        private final List<UUID> submittedConversationIds = new ArrayList<>();
        private final List<String> submittedContents = new ArrayList<>();
        private final List<UUID> traceRunIds = new ArrayList<>();
        private final List<UUID> logRunIds = new ArrayList<>();
        private final List<UUID> followedTraceRunIds = new ArrayList<>();
        private final List<UUID> followedLogRunIds = new ArrayList<>();
        private int identityCalls;
        private String traceData = """
                {"kind":"EVENT","event":{"type":"NODE_PROGRESS",\
                "nodeName":"planner","summary":"正在规划"}}
                """;
        private String logData = """
                {"kind":"LOG","event":{"nodeName":"ops",\
                "stream":"STDOUT","text":"编译中\\n"}}
                """;
        private String finalAnswer = "最终回答";

        private FakeClient(boolean withWorkspace) {
            if (withWorkspace) {
                workspaces.add(workspace());
                conversations.add(conversation(FIRST_CONVERSATION_ID, "架构咨询"));
                conversations.add(conversation(SECOND_CONVERSATION_ID, "代码审查"));
            }
        }

        @Override
        public Actor identity() {
            identityCalls++;
            return new Actor("local", "Local User");
        }

        @Override
        public List<Workspace> listWorkspaces() {
            return List.copyOf(workspaces);
        }

        @Override
        public Workspace createWorkspace(
                String displayName,
                String workspacePath,
                String repositoryId) {
            workspaceCreates.add(new WorkspaceCreate(displayName, workspacePath, repositoryId));
            Workspace created = workspace();
            workspaces.add(created);
            return created;
        }

        @Override
        public List<Conversation> listConversations(UUID workspaceId) {
            return List.copyOf(conversations);
        }

        @Override
        public Conversation createConversation(UUID workspaceId) {
            createdConversationWorkspaceIds.add(workspaceId);
            Conversation created = conversation(NEW_CONVERSATION_ID, "新建会话");
            conversations.add(0, created);
            return created;
        }

        @Override
        public List<Turn> listTurns(UUID conversationId) {
            if (SECOND_CONVERSATION_ID.equals(conversationId)) {
                return List.of(turn(SECOND_CONVERSATION_ID, "历史回答", "COMPLETED"));
            }
            if (!NEW_CONVERSATION_ID.equals(conversationId)
                    || submittedConversationIds.isEmpty()) {
                return List.of();
            }
            int reads = turnReads.merge(conversationId, 1, Integer::sum);
            return List.of(turn(
                    NEW_CONVERSATION_ID,
                    reads > 1 ? finalAnswer : null,
                    reads > 1 ? "COMPLETED" : "RUNNING"));
        }

        @Override
        public Turn submitTurn(UUID conversationId, String content, String reviewerUrl) {
            submittedConversationIds.add(conversationId);
            submittedContents.add(content);
            return turn(conversationId, null, "RUNNING");
        }

        @Override
        public Run getRun(UUID runId) {
            return new Run(runId, 2, "code-agent", "COMPLETED",
                    new ObjectMapper().createObjectNode(), null, null, null);
        }

        @Override
        public List<SseEventReader.SseEvent> readTrace(UUID runId) {
            traceRunIds.add(runId);
            return List.of(new SseEventReader.SseEvent("1", "trace", traceData));
        }

        @Override
        public List<SseEventReader.SseEvent> readLogs(UUID runId) {
            logRunIds.add(runId);
            return List.of(new SseEventReader.SseEvent("2", "log", logData));
        }

        @Override
        public void followTrace(
                UUID runId,
                Consumer<SseEventReader.SseEvent> eventConsumer) {
            followedTraceRunIds.add(runId);
            eventConsumer.accept(new SseEventReader.SseEvent("1", "trace", traceData));
        }

        @Override
        public void followLogs(
                UUID runId,
                Consumer<SseEventReader.SseEvent> eventConsumer) {
            followedLogRunIds.add(runId);
            eventConsumer.accept(new SseEventReader.SseEvent("2", "log", logData));
        }

        private Workspace workspace() {
            return new Workspace(
                    WORKSPACE_ID, "local", "Agent4J", "/agent-workspace", "local",
                    "OWNER", "2026-08-10T00:00:00Z", "2026-08-10T00:00:00Z");
        }

        private Conversation conversation(UUID conversationId, String title) {
            return new Conversation(
                    conversationId, WORKSPACE_ID, "local", title, "ACTIVE",
                    "2026-08-10T00:00:00Z", "2026-08-10T00:00:00Z");
        }

        private Turn turn(UUID conversationId, String assistantContent, String status) {
            return new Turn(
                    UUID.fromString("b93102ac-59b4-43ee-b312-2ac9b8f353b8"),
                    conversationId,
                    1,
                    "解释当前架构",
                    assistantContent,
                    RUN_ID,
                    status,
                    null,
                    "2026-08-10T00:00:00Z",
                    "COMPLETED".equals(status) ? "2026-08-10T00:00:01Z" : null);
        }
    }

    private record WorkspaceCreate(
            String displayName,
            String workspacePath,
            String repositoryId) {
    }
}
