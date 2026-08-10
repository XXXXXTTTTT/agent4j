package com.agent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** Agent4J CLI 进程入口。 */
public final class Main {

    private Main() {
    }

    /** 启动 CLI 进程。 */
    public static void main(String[] arguments) {
        int exitCode = run(arguments, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] arguments, PrintStream stdout, PrintStream stderr) {
        try {
            CliArguments parsed = CliArguments.parse(arguments);
            return switch (parsed.command()) {
                case CHAT -> runChat(parsed, stdout);
                case SERVE -> runServe(parsed, stdout);
                case CONVERSATIONS -> runConversations(parsed, stdout);
            };
        } catch (RuntimeException exception) {
            stderr.println(Agent4jCli.sanitize(renderFailure(exception)));
            return 1;
        }
    }

    private static int runChat(CliArguments arguments, PrintStream stdout) {
        Agent4jClient client = client(arguments.server());
        return new Agent4jCli(
                client,
                new InputStreamReader(System.in, StandardCharsets.UTF_8),
                stdout::println).run(arguments.workspace());
    }

    private static int runServe(CliArguments arguments, PrintStream stdout) {
        new ComposeLauncher(stdout::println).launch(
                arguments.workspace(), arguments.composeFile(), Duration.ofMinutes(5));
        return 0;
    }

    private static int runConversations(CliArguments arguments, PrintStream stdout) {
        Agent4jClient client = client(arguments.server());
        Agent4jClient.Actor actor = client.identity();
        print(stdout, "用户: " + actor.displayName() + " (" + actor.userId() + ")");
        for (Agent4jClient.Workspace workspace : client.listWorkspaces()) {
            print(stdout, "工作区: " + workspace.displayName() + " ["
                    + workspace.workspacePath() + "] " + workspace.permission());
            List<Agent4jClient.Conversation> conversations =
                    client.listConversations(workspace.workspaceId());
            for (Agent4jClient.Conversation conversation : conversations) {
                print(stdout, "  " + conversation.conversationId() + " "
                        + conversation.status() + " " + conversation.title());
            }
        }
        return 0;
    }

    private static Agent4jClient client(URI server) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        return new Agent4jHttpClient(httpClient, server, new ObjectMapper());
    }

    private static String renderFailure(RuntimeException exception) {
        if (exception instanceof Agent4jHttpException http) {
            return "HTTP " + http.statusCode() + "\n" + http.responseBody();
        }
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }

    private static void print(PrintStream output, String value) {
        output.println(Agent4jCli.sanitize(value));
    }
}
