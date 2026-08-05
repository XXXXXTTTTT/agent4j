package com.agent.core.nodes;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.Node;
import com.agent.core.llm.ChatMessage;
import com.agent.core.llm.ModelRequest;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.RoutedCompletion;
import com.agent.core.llm.TaskType;
import com.agent.sandbox.ast.AstService;
import com.agent.sandbox.ast.WorkspaceFile;
import com.agent.sandbox.ast.WorkspaceSnapshot;
import com.agent.sandbox.ast.WorkspaceSnapshotService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** 应用状态中 Unified Diff 的代码修改节点。 */
public final class CoderNode implements Node {

    public static final String WORKSPACE_PATH_KEY = "coder.workspacePath";
    public static final String UNIFIED_DIFF_KEY = "coder.unifiedDiff";
    public static final String UPDATED_FILES_KEY = "coder.updatedFiles";
    public static final String REQUEST_KEY = "coder.request";
    public static final String RESPONSE_KEY = "coder.response";
    public static final String MODEL_KEY = "coder.model";
    public static final String SUMMARY_KEY = "coder.summary";
    public static final String COMMAND_KEY = "coder.command";
    public static final String ERROR_KEY = "coder.error";

    private final AstService astService;
    private final ModelRouter modelRouter;
    private final ObjectMapper objectMapper;
    private final WorkspaceSnapshotService snapshotService;
    private final ObjectReader changeReader;

    /**
     * 创建代码修改节点。
     *
     * @param astService AST 与 Diff 服务
     */
    public CoderNode(AstService astService) {
        this.astService = Objects.requireNonNull(astService, "astService 不能为空");
        this.modelRouter = null;
        this.objectMapper = null;
        this.snapshotService = null;
        this.changeReader = null;
    }

    /** 创建执行模型生成、快照、Diff 应用和命令提取的生产 Coder 节点。 */
    public CoderNode(
            AstService astService,
            ModelRouter modelRouter,
            ObjectMapper objectMapper,
            WorkspaceSnapshotService snapshotService) {
        this.astService = Objects.requireNonNull(astService, "astService 不能为空");
        this.modelRouter = Objects.requireNonNull(modelRouter, "modelRouter 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.snapshotService = Objects.requireNonNull(
                snapshotService, "snapshotService 不能为空");
        this.changeReader = objectMapper.readerFor(CodeChange.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);
    }

    /**
     * 应用状态中的补丁，并返回包含更新文件的新状态。
     *
     * @param state 输入状态
     * @return 节点执行后的新状态
     */
    @Override
    public AgentState execute(AgentState state) {
        Objects.requireNonNull(state, "state 不能为空");
        if (modelRouter != null) {
            return generateAndApply(state);
        }
        return applyExistingDiff(state);
    }

    private AgentState applyExistingDiff(AgentState state) {
        try {
            Path workspace = workspace(state);
            String unifiedDiff = requireVariable(state, UNIFIED_DIFF_KEY);
            return withUpdatedFiles(state, workspace, unifiedDiff);
        } catch (Exception exception) {
            return state
                    .withVariable(ERROR_KEY, stackTrace(exception))
                    .withTraceEntry("coder");
        }
    }

    private AgentState generateAndApply(AgentState state) {
        AgentState output = state;
        try {
            Path workspace = workspace(state);
            String task = requireVariable(state, PlannerNode.TASK_KEY);
            String plan = requireVariable(state, PlannerNode.PLAN_KEY);
            WorkspaceSnapshot snapshot = snapshotService.capture(workspace);
            String requestText = buildRequest(task, plan, snapshot, state);
            output = output.withVariable(REQUEST_KEY, requestText);
            ModelRequest request = new ModelRequest(
                    List.of(
                            ChatMessage.system("你是代码修改节点。只返回 JSON 对象，字段严格为 summary、unifiedDiff、command。unifiedDiff 必须是可应用的 Unified Diff，command 必须是用于验证修改的 Bash 命令。"),
                            ChatMessage.user(requestText)),
                    List.of(),
                    null,
                    0.0);
            RoutedCompletion completion = modelRouter.complete(TaskType.CODE, request);
            ChatMessage message = completion.response().choices().getFirst().message();
            if (!(message.content() instanceof ChatMessage.TextContent textContent)) {
                throw new IllegalStateException("代码模型响应 content 必须是 TextContent");
            }
            String responseText = textContent.text();
            CodeChange change = changeReader.readValue(responseText);
            output = output
                    .withVariable(RESPONSE_KEY, responseText)
                    .withVariable(MODEL_KEY, completion.model())
                    .withVariable(SUMMARY_KEY, change.summary())
                    .withVariable(UNIFIED_DIFF_KEY, change.unifiedDiff())
                    .withVariable(COMMAND_KEY, change.command())
                    .withVariable(OpsNode.COMMAND_KEY, change.command());
            return withUpdatedFiles(output, workspace, change.unifiedDiff());
        } catch (Exception exception) {
            return output
                    .withVariable(ERROR_KEY, stackTrace(exception))
                    .withTraceEntry("coder");
        }
    }

    private AgentState withUpdatedFiles(
            AgentState state,
            Path workspace,
            String unifiedDiff) {
        List<Path> updatedFiles = astService.applyDiff(workspace, unifiedDiff);
        String relativeFiles = updatedFiles.stream()
                .map(workspace::relativize)
                .map(Path::toString)
                .map(path -> path.replace('\\', '/'))
                .collect(Collectors.joining("\n"));
        return state
                .withVariable(UPDATED_FILES_KEY, relativeFiles)
                .withTraceEntry("coder");
    }

    private Path workspace(AgentState state) {
        return Path.of(requireVariable(state, WORKSPACE_PATH_KEY))
                .toAbsolutePath()
                .normalize();
    }

    private String buildRequest(
            String task,
            String plan,
            WorkspaceSnapshot snapshot,
            AgentState state) {
        StringBuilder request = new StringBuilder()
                .append("用户任务:\n").append(task)
                .append("\n\n执行计划:\n").append(plan)
                .append("\n\n工作区文件:\n");
        for (WorkspaceFile file : snapshot.files()) {
            request.append("--- ").append(file.relativePath()).append(" ---\n")
                    .append(file.content()).append('\n');
        }
        appendState(request, state, OpsNode.EXIT_CODE_KEY);
        appendState(request, state, OpsNode.STDOUT_KEY);
        appendState(request, state, OpsNode.STDERR_KEY);
        appendState(request, state, ReviewerNode.FEEDBACK_KEY);
        return request.toString();
    }

    private void appendState(StringBuilder request, AgentState state, String key) {
        String value = state.variables().get(key);
        if (value != null && !value.isBlank()) {
            request.append("\n").append(key).append(":\n").append(value).append('\n');
        }
    }

    private String requireVariable(AgentState state, String key) {
        String value = state.variables().get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少状态变量: " + key);
        }
        return value;
    }

    private String stackTrace(Exception exception) {
        StringWriter writer = new StringWriter();
        exception.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private record CodeChange(String summary, String unifiedDiff, String command) {

        private CodeChange {
            if (summary == null || summary.isBlank()) {
                throw new IllegalArgumentException("代码变更 summary 不能为空");
            }
            if (unifiedDiff == null || unifiedDiff.isBlank()) {
                throw new IllegalArgumentException("代码变更 unifiedDiff 不能为空");
            }
            if (command == null || command.isBlank()) {
                throw new IllegalArgumentException("代码变更 command 不能为空");
            }
        }
    }
}
