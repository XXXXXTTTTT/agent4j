package com.agent.core.nodes;

import com.agent.core.cli.CliCommandCatalog;
import com.agent.core.cli.CliCommandDefinition;
import com.agent.core.engine.AgentState;
import com.agent.core.engine.Node;
import com.agent.core.engine.NodeExecutionContext;
import com.agent.core.intent.RequiredCapability;
import com.agent.core.llm.ChatMessage;
import com.agent.core.llm.ModelRequest;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.RoutedCompletion;
import com.agent.core.llm.TaskType;
import com.agent.core.tool.HarnessToolExecutor;
import com.agent.core.tool.ToolCall;
import com.agent.core.tool.ToolInvocationContext;
import com.agent.core.tool.ToolRegistry;
import com.agent.core.tool.ToolResult;
import com.agent.core.tool.ToolResultStatus;
import com.agent.core.tool.builtin.CodePatchTool;
import com.agent.sandbox.ast.AstService;
import com.agent.sandbox.ast.AstService.AppliedDiff;
import com.agent.sandbox.ast.WorkspaceFile;
import com.agent.sandbox.ast.WorkspaceSnapshot;
import com.agent.sandbox.ast.WorkspaceSnapshotService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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
    public static final String COMMAND_NAME_KEY = "coder.commandName";
    public static final String COMMAND_ARGUMENTS_KEY = "coder.commandArguments";
    public static final String KNOWLEDGE_FINGERPRINT_KEY = "coder.knowledgeFingerprint";
    public static final String KNOWLEDGE_SOURCES_KEY = "coder.knowledgeSources";
    public static final String ATTEMPT_KEY = "coder.attempt";
    public static final String ERROR_KEY = "coder.error";

    private final AstService astService;
    private final ModelRouter modelRouter;
    private final WorkspaceSnapshotService snapshotService;
    private final ObjectReader changeReader;
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final HarnessToolExecutor harnessToolExecutor;
    private final CliCommandCatalog commandCatalog;

    /**
     * 创建代码修改节点。
     *
     * @param astService AST 与 Diff 服务
     */
    public CoderNode(AstService astService) {
        this.astService = Objects.requireNonNull(astService, "astService 不能为空");
        this.modelRouter = null;
        this.snapshotService = null;
        this.changeReader = null;
        this.objectMapper = null;
        this.toolRegistry = null;
        this.harnessToolExecutor = null;
        this.commandCatalog = null;
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
        this.toolRegistry = null;
        this.harnessToolExecutor = null;
        this.commandCatalog = null;
        this.changeReader = objectMapper.readerFor(CodeChange.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);
    }

    /** 创建通过受治理工具应用 Diff 的生产 Coder 节点。 */
    public CoderNode(
            AstService astService,
            ModelRouter modelRouter,
            ObjectMapper objectMapper,
            WorkspaceSnapshotService snapshotService,
            ToolRegistry toolRegistry) {
        this(astService, modelRouter, objectMapper, snapshotService, toolRegistry, null);
    }

    /** 创建通过受治理工具应用 Diff，并向模型公开精确 CLI 目录的生产 Coder 节点。 */
    public CoderNode(
            AstService astService,
            ModelRouter modelRouter,
            ObjectMapper objectMapper,
            WorkspaceSnapshotService snapshotService,
            ToolRegistry toolRegistry,
            CliCommandCatalog commandCatalog) {
        this.astService = Objects.requireNonNull(astService, "astService 不能为空");
        this.modelRouter = Objects.requireNonNull(modelRouter, "modelRouter 不能为空");
        Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.snapshotService = Objects.requireNonNull(
                snapshotService, "snapshotService 不能为空");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry 不能为空");
        this.harnessToolExecutor = new HarnessToolExecutor(toolRegistry);
        this.commandCatalog = commandCatalog;
        this.objectMapper = objectMapper;
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
            int attempt = parseAttempt(state) + 1;
            NodeExecutionContext.progress("正在准备第 " + attempt + " 次代码修改");
            output = output.withVariable(ATTEMPT_KEY, Integer.toString(attempt));
            Path workspace = workspace(state);
            String task = requireVariable(state, PlannerNode.TASK_KEY);
            String plan = requireVariable(state, PlannerNode.PLAN_KEY);
            WorkspaceSnapshot snapshot = snapshotService.captureForPrompt(workspace);
            NodeExecutionContext.progress("工作区快照已就绪，共 " + snapshot.files().size() + " 个文件");
            String requestText = buildRequest(task, plan, snapshot, state);
            output = output.withVariable(REQUEST_KEY, requestText);
            ModelRequest request = new ModelRequest(
                    List.of(
                            ChatMessage.system(systemPrompt()),
                            ChatMessage.user(requestText)),
                    List.of(),
                    null,
                    0.0);
            GeneratedCodeChange generated = generateCodeChange(request);
            RoutedCompletion completion = generated.completion();
            String responseText = generated.responseText();
            CodeChange change = generated.change();
            String commandArguments = objectMapper.writeValueAsString(
                    change.commandArguments());
            output = output
                    .withVariable(RESPONSE_KEY, responseText)
                    .withVariable(MODEL_KEY, completion.model())
                    .withVariable(SUMMARY_KEY, change.summary())
                    .withVariable(UNIFIED_DIFF_KEY, change.unifiedDiff())
                    .withVariable(COMMAND_NAME_KEY, change.commandName())
                    .withVariable(COMMAND_ARGUMENTS_KEY, commandArguments)
                    .withVariable(OpsNode.COMMAND_NAME_KEY, change.commandName())
                    .withVariable(OpsNode.COMMAND_ARGUMENTS_KEY, commandArguments)
                    .withVariable(KNOWLEDGE_FINGERPRINT_KEY,
                            state.variables().getOrDefault(
                                    PlannerNode.KNOWLEDGE_FINGERPRINT_KEY, ""))
                    .withVariable(KNOWLEDGE_SOURCES_KEY,
                            state.variables().getOrDefault(
                                    PlannerNode.KNOWLEDGE_SOURCES_KEY, "0"));
            if (toolRegistry == null) {
                return withUpdatedFiles(output, workspace, change.unifiedDiff());
            }
            ToolResult patchResult = executePatch(output, workspace, change, attempt);
            if (patchResult.status() != ToolResultStatus.SUCCEEDED) {
                return output
                        .withVariable(ERROR_KEY, patchResult.errorStack())
                        .withTraceEntry("coder");
            }
            String normalizedDiff = patchResult.output().path("unifiedDiff").textValue();
            if (normalizedDiff == null || normalizedDiff.isBlank()) {
                throw new IllegalStateException("code.apply-diff 返回 unifiedDiff 必须是非空字符串");
            }
            String updatedFiles = updatedFiles(patchResult.output());
            return output
                    .withVariable(UNIFIED_DIFF_KEY, normalizedDiff)
                    .withVariable(UPDATED_FILES_KEY, updatedFiles)
                    .withTraceEntry("coder");
        } catch (Exception exception) {
            return output
                    .withVariable(ERROR_KEY, stackTrace(exception))
                    .withTraceEntry("coder");
        }
    }

    private ToolResult executePatch(
            AgentState state,
            Path workspace,
            CodeChange change,
            int attempt) throws Exception {
        JsonNode arguments = objectMapper.createObjectNode()
                .put("unifiedDiff", change.unifiedDiff());
        ToolCall call = new ToolCall(
                "coder-patch-" + attempt,
                CodePatchTool.NAME,
                arguments);
        UUID runId = NodeExecutionContext.current()
                .map(NodeExecutionContext::runId)
                .orElseGet(() -> UUID.nameUUIDFromBytes(
                        workspace.toString().getBytes(StandardCharsets.UTF_8)));
        String nodeName = NodeExecutionContext.current()
                .map(NodeExecutionContext::nodeName)
                .orElse("coder");
        String userId = requireVariable(state, PlannerNode.USER_ID_KEY);
        ToolInvocationContext context = new ToolInvocationContext(
                runId,
                nodeName,
                userId,
                workspace,
                Set.of(RequiredCapability.CODE_READ, RequiredCapability.CODE_WRITE),
                true);
        if (NodeExecutionContext.current().isPresent()) {
            return harnessToolExecutor.execute(call, context);
        }
        return toolRegistry.execute(call, context);
    }

    /**
     * 结构化代码响应不完整时仅要求模型纠正一次格式；补丁和工具失败仍按原链路处理。
     */
    private GeneratedCodeChange generateCodeChange(ModelRequest request) throws Exception {
        RoutedCompletion completion = modelRouter.complete(TaskType.CODE, request);
        NodeExecutionContext.progress("代码模型已返回变更方案");
        String responseText = responseText(completion);
        try {
            return new GeneratedCodeChange(completion, responseText, parseCodeChange(responseText));
        } catch (java.io.IOException | IllegalArgumentException formatFailure) {
            NodeExecutionContext.progress("代码模型响应格式无效，正在请求一次纠正");
            String correction = "代码模型响应字段格式错误：" + formatFailure.getMessage()
                    + "。必须只返回包含非空 summary、unifiedDiff、commandName 和 commandArguments 的 JSON 对象。"
                    + "unifiedDiff 必须是可应用的 Unified Diff，commandArguments 必须是 JSON 数组。";
            List<ChatMessage> correctionMessages = new java.util.ArrayList<>(request.messages());
            correctionMessages.add(ChatMessage.assistant(responseText));
            correctionMessages.add(ChatMessage.user(correction));
            ModelRequest correctionRequest = new ModelRequest(
                    List.copyOf(correctionMessages), request.tools(), request.toolChoice(),
                    request.temperature(), request.modelGroupId());
            RoutedCompletion corrected = modelRouter.complete(TaskType.CODE, correctionRequest);
            NodeExecutionContext.progress("代码模型已返回纠正后的变更方案");
            String correctedText = responseText(corrected);
            return new GeneratedCodeChange(corrected, correctedText, parseCodeChange(correctedText));
        }
    }

    private String responseText(RoutedCompletion completion) {
        ChatMessage message = completion.response().choices().getFirst().message();
        if (!(message.content() instanceof ChatMessage.TextContent textContent)) {
            throw new IllegalStateException("代码模型响应 content 必须是 TextContent");
        }
        return textContent.text();
    }

    private CodeChange parseCodeChange(String responseText) throws java.io.IOException {
        JsonNode responseNode = objectMapper.readTree(responseText);
        if (!(responseNode instanceof ObjectNode responseObject)) {
            throw new IllegalArgumentException("代码模型响应必须是 JSON 对象");
        }
        ObjectNode normalized = normalizeCommandArguments(objectMapper, responseObject);
        requireNonBlankText(normalized, "summary");
        requireNonBlankText(normalized, "unifiedDiff");
        requireNonBlankText(normalized, "commandName");
        JsonNode commandArguments = normalized.get("commandArguments");
        if (commandArguments == null || !commandArguments.isArray()) {
            throw new IllegalArgumentException("代码模型响应字段 commandArguments 必须是 JSON 数组");
        }
        return changeReader.readValue(normalized.toString());
    }

    private void requireNonBlankText(ObjectNode response, String field) {
        JsonNode value = response.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("代码模型响应字段 " + field + " 必须是非空字符串");
        }
    }

    private String updatedFiles(JsonNode output) {
        JsonNode files = output.path("updatedFiles");
        if (!files.isArray()) {
            throw new IllegalStateException("code.apply-diff 返回 updatedFiles 必须是数组");
        }
        StringBuilder result = new StringBuilder();
        for (JsonNode file : files) {
            if (!file.isTextual() || file.textValue().isBlank()) {
                throw new IllegalStateException("code.apply-diff 返回文件路径必须是非空字符串");
            }
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(file.textValue());
        }
        return result.toString();
    }

    private AgentState withUpdatedFiles(
            AgentState state,
            Path workspace,
            String unifiedDiff) {
        AppliedDiff applied = astService.applyDiffWithEvidence(workspace, unifiedDiff);
        String relativeFiles = applied.updatedFiles().stream()
                .map(workspace::relativize)
                .map(Path::toString)
                .map(path -> path.replace('\\', '/'))
                .collect(Collectors.joining("\n"));
        return state
                .withVariable(UNIFIED_DIFF_KEY, applied.unifiedDiff())
                .withVariable(UPDATED_FILES_KEY, relativeFiles)
                .withTraceEntry("coder");
    }

    static ObjectNode normalizeCommandArguments(
            ObjectMapper objectMapper,
            ObjectNode response) throws java.io.IOException {
        Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        ObjectNode normalized = Objects.requireNonNull(
                response, "response 不能为空").deepCopy();
        JsonNode commandArguments = normalized.get("commandArguments");
        if (commandArguments == null || !commandArguments.isTextual()) {
            return normalized;
        }
        JsonNode decoded = objectMapper.readTree(commandArguments.textValue());
        if (decoded == null || !decoded.isArray()) {
            throw new IllegalArgumentException(
                    "代码变更 commandArguments 字符串内容必须是 JSON 数组");
        }
        normalized.set("commandArguments", decoded);
        return normalized;
    }

    private Path workspace(AgentState state) {
        return Path.of(requireVariable(state, WORKSPACE_PATH_KEY))
                .toAbsolutePath()
                .normalize();
    }

    private String systemPrompt() {
        StringBuilder prompt = new StringBuilder()
                .append("你是代码修改节点。只返回 JSON 对象，字段严格为 summary、unifiedDiff、commandName、commandArguments。")
                .append("unifiedDiff 必须是可应用的 Unified Diff；commandArguments 必须是 JSON 数组，不能把数组再次编码成字符串。")
                .append("禁止返回裸 Bash command 字段。");
        if (commandCatalog == null) {
            return prompt.append("commandName 必须是已注册 CLI 命令名。").toString();
        }
        prompt.append("commandName 必须精确等于下列目录中的 name；fixedArguments 由系统预置，commandArguments 只填写追加参数。")
                .append("\n允许的 CLI 命令目录：");
        for (CliCommandDefinition definition : commandCatalog.list()) {
            prompt.append("\n- name=").append(definition.name())
                    .append(", executable=").append(definition.executable())
                    .append(", fixedArguments=").append(definition.fixedArguments());
        }
        return prompt.toString();
    }

    private String buildRequest(
            String task,
            String plan,
            WorkspaceSnapshot snapshot,
            AgentState state) {
        StringBuilder request = new StringBuilder()
                .append("用户任务:\n").append(task)
                .append("\n\n执行计划:\n").append(plan)
                .append("\n\n工作区文件（受文件数和字节预算限制的部分视图）:\n");
        for (WorkspaceFile file : snapshot.files()) {
            request.append("--- ").append(file.relativePath()).append(" ---\n")
                    .append(file.content()).append('\n');
        }
        appendState(request, state, OpsNode.EXIT_CODE_KEY);
        appendState(request, state, OpsNode.STDOUT_KEY);
        appendState(request, state, OpsNode.STDERR_KEY);
        appendState(request, state, ReviewerNode.FEEDBACK_KEY);
        appendState(request, state, PlannerNode.KNOWLEDGE_CONTEXT_KEY);
        appendState(request, state, PlannerNode.KNOWLEDGE_FINGERPRINT_KEY);
        appendState(request, state, PlannerNode.KNOWLEDGE_SOURCES_KEY);
        return request.toString();
    }

    private void appendState(StringBuilder request, AgentState state, String key) {
        String value = state.variables().get(key);
        if (value != null && !value.isBlank()) {
            request.append("\n").append(key).append(":\n").append(value).append('\n');
        }
    }

    private int parseAttempt(AgentState state) {
        String value = state.variables().get(ATTEMPT_KEY);
        if (value == null) {
            return 0;
        }
        int attempt = Integer.parseInt(value);
        if (attempt < 0) {
            throw new IllegalArgumentException(ATTEMPT_KEY + " 不能为负数");
        }
        return attempt;
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

    private record CodeChange(
            String summary,
            String unifiedDiff,
            String commandName,
            List<String> commandArguments) {

        private CodeChange {
            if (summary == null || summary.isBlank()) {
                throw new IllegalArgumentException("代码变更 summary 不能为空");
            }
            if (unifiedDiff == null || unifiedDiff.isBlank()) {
                throw new IllegalArgumentException("代码变更 unifiedDiff 不能为空");
            }
            if (commandName == null || commandName.isBlank()) {
                throw new IllegalArgumentException("代码变更 commandName 不能为空");
            }
            commandArguments = List.copyOf(Objects.requireNonNull(
                    commandArguments, "代码变更 commandArguments 不能为空"));
            if (commandArguments.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("代码变更 commandArguments 不能包含 null");
            }
        }
    }

    private record GeneratedCodeChange(
            RoutedCompletion completion,
            String responseText,
            CodeChange change) {
    }
}
