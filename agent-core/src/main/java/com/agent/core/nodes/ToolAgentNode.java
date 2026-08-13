package com.agent.core.nodes;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.Node;
import com.agent.core.engine.NodeExecutionContext;
import com.agent.core.intent.RequiredCapability;
import com.agent.core.llm.ChatMessage;
import com.agent.core.llm.LlmClient;
import com.agent.core.llm.ModelRequest;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.RoutedCompletion;
import com.agent.core.llm.TaskType;
import com.agent.core.skill.SkillCatalog;
import com.agent.core.skill.SkillCatalogProvider;
import com.agent.core.skill.SkillCatalogSnapshot;
import com.agent.core.skill.SkillCatalogSnapshotCodec;
import com.agent.core.skill.SkillPromptContext;
import com.agent.core.skill.SkillPromptJson;
import com.agent.core.skill.SkillToolMetadata;
import com.agent.core.tool.HarnessToolExecutor;
import com.agent.core.tool.ToolCall;
import com.agent.core.tool.ToolDefinition;
import com.agent.core.tool.ToolInvocationContext;
import com.agent.core.tool.ToolRegistry;
import com.agent.core.tool.ToolResult;
import com.agent.core.tool.ToolResultStatus;
import com.agent.core.tool.builtin.ImageGenerationTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.EnumSet;
import java.util.function.Function;

/** 执行不属于代码、终端或浏览器链的通用受治理工具循环。 */
public final class ToolAgentNode implements Node {

    public static final String REQUEST_KEY = "tool.request";
    public static final String RESPONSE_KEY = "tool.response";
    public static final String MODEL_KEY = "tool.model";
    public static final String RESULT_KEY = "tool.result";
    public static final String ERROR_KEY = "tool.error";
    public static final String APPROVAL_KEY = "tool.approvalGranted";
    public static final String ACTIVE_SKILLS_KEY = "skill.active";
    public static final String SKILL_FINGERPRINT_KEY = "skill.fingerprint";
    public static final String SKILL_CATALOG_SNAPSHOT_KEY = "skill.catalogSnapshot";
    private static final String NODE_NAME = "tool-agent";

    private final Function<ModelRequest, RoutedCompletion> completer;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final SkillCatalog skillCatalog;
    private final SkillCatalogSnapshotCodec skillCatalogSnapshotCodec;
    private final int maxSteps;

    public ToolAgentNode(
            ModelRouter modelRouter,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            SkillCatalog skillCatalog,
            int maxSteps) {
        this(request -> modelRouter.complete(TaskType.CODE, request),
                toolRegistry, objectMapper, skillCatalog, maxSteps);
    }

    ToolAgentNode(
            Function<ModelRequest, RoutedCompletion> completer,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            SkillCatalog skillCatalog,
            int maxSteps) {
        this.completer = Objects.requireNonNull(completer, "completer 不能为空");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.skillCatalog = skillCatalog;
        this.skillCatalogSnapshotCodec = new SkillCatalogSnapshotCodec(objectMapper);
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps 必须大于 0");
        }
        this.maxSteps = maxSteps;
    }

    @Override
    public AgentState execute(AgentState state) {
        Objects.requireNonNull(state, "state 不能为空");
        AgentState output = state;
        try {
            String task = required(output, PlannerNode.TASK_KEY);
            SkillCatalog effectiveCatalog = resolveCatalog(output);
            SkillPromptContext skills = effectiveCatalog == null
                    ? null : effectiveCatalog.resolve(task, Set.of());
            output = withSkillEvidence(output, skills);
            List<ToolDefinition> definitions = exposedDefinitions(skills);
            if (definitions.isEmpty()) {
                throw new IllegalStateException("当前没有可调用工具");
            }
            if (isDirectImageSkill(skills, definitions)) {
                return executeDirectImage(output, task, definitions.getFirst());
            }
            Map<String, String> protocolToRegistryName = toolNameMapping(definitions);
            List<LlmClient.Tool> tools = definitions.stream()
                    .map(definition -> LlmClient.Tool.function(
                            protocolName(definition.name(), protocolToRegistryName),
                            definition.description(), definition.inputSchema()))
                    .toList();
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.system(systemPrompt(skills, definitions, protocolToRegistryName)));
            messages.addAll(output.messages());
            appendTaskIfMissing(messages, task);
            for (int step = 1; step <= maxSteps; step++) {
                NodeExecutionContext.progress("正在执行工具 Agent 第 " + step + " 步");
                String requestText = objectMapper.writeValueAsString(messages);
                output = output.withVariable(REQUEST_KEY, requestText);
                RoutedCompletion completion = completer.apply(
                        new ModelRequest(List.copyOf(messages), tools, null, 0.0));
                ChatMessage message = completion.response().choices().getFirst().message();
                output = output.withVariable(MODEL_KEY, completion.model())
                        .withVariable(RESPONSE_KEY, messageText(message));
                if (message.toolCalls().isEmpty()) {
                    String response = messageText(message);
                    if (response.isBlank()) {
                        throw new IllegalStateException("工具 Agent 最终回答不能为空");
                    }
                    return output.withMessage(ChatMessage.assistant(response))
                            .withVariable(PlannerNode.RESPONSE_KEY, response)
                            .withVariable(PlannerNode.FINAL_RESPONSE_KEY, response)
                            .withTraceEntry(NODE_NAME);
                }
                messages.add(message);
                for (ChatMessage.ToolCall call : message.toolCalls()) {
                    String registryName = protocolToRegistryName.get(call.function().name());
                    if (registryName == null) {
                        throw new IllegalArgumentException("模型返回了未声明的工具: " + call.function().name());
                    }
                    ToolCall toolCall = new ToolCall(
                            call.id(), registryName, objectMapper.readTree(call.function().arguments()));
                    ToolResult result = executeTool(output, toolCall);
                    String resultText = objectMapper.writeValueAsString(result.output());
                    output = output.withVariable(RESULT_KEY, resultText);
                    messages.add(ChatMessage.tool(call.id(), resultText));
                }
            }
            throw new IllegalStateException("工具 Agent 达到 maxSteps=" + maxSteps);
        } catch (Exception exception) {
            String response = userFacingFailure(exception);
            return output.withMessage(ChatMessage.assistant(response))
                    .withVariable(ERROR_KEY, stackTrace(exception))
                    .withVariable(PlannerNode.RESPONSE_KEY, response)
                    .withVariable(PlannerNode.FINAL_RESPONSE_KEY, response)
                    .withTraceEntry(NODE_NAME);
        }
    }

    private SkillCatalog resolveCatalog(AgentState state) {
        String encoded = state.variables().get(SKILL_CATALOG_SNAPSHOT_KEY);
        if (encoded == null || encoded.isBlank()) {
            return skillCatalog;
        }
        String actor = state.variables().get(PlannerNode.USER_ID_KEY);
        String workspace = state.variables().get("conversation.workspaceId");
        if (actor == null || workspace == null) {
            throw new IllegalArgumentException("Skill 目录快照缺少绑定身份");
        }
        SkillCatalogSnapshot snapshot = skillCatalogSnapshotCodec.decode(
                encoded, actor, UUID.fromString(workspace), toolRegistry);
        return snapshot.definitions().isEmpty()
                ? null : new SkillCatalog(snapshot.definitions(), toolRegistry, objectMapper);
    }

    private ToolResult executeTool(AgentState state, ToolCall call) throws Exception {
        UUID runId = NodeExecutionContext.current().map(NodeExecutionContext::runId)
                .orElseGet(UUID::randomUUID);
        String nodeName = NodeExecutionContext.current().map(NodeExecutionContext::nodeName)
                .orElse(NODE_NAME);
        String userId = state.variables().getOrDefault(PlannerNode.USER_ID_KEY, "local");
        Path workspace = Path.of(state.variables().getOrDefault(
                CoderNode.WORKSPACE_PATH_KEY, "."));
        EnumSet<RequiredCapability> capabilities = EnumSet.of(RequiredCapability.TOOL);
        String declared = state.variables().get(PlannerNode.REQUIRED_CAPABILITIES_KEY);
        if (declared != null && !declared.isBlank()) {
            for (String value : declared.split(",")) {
                if (!value.isBlank()) capabilities.add(RequiredCapability.valueOf(value));
            }
        }
        ToolInvocationContext context = new ToolInvocationContext(
                runId, nodeName, userId, workspace, capabilities,
                Boolean.parseBoolean(state.variables().getOrDefault(APPROVAL_KEY, "false")));
        if (NodeExecutionContext.current().isPresent()) {
            return new HarnessToolExecutor(toolRegistry).execute(call, context);
        }
        return toolRegistry.execute(call, context);
    }

    private boolean isDirectImageSkill(
            SkillPromptContext skills,
            List<ToolDefinition> definitions) {
        return skills != null
                && skills.activatedSkills().size() == 1
                && definitions.size() == 1
                && ImageGenerationTool.NAME.equals(definitions.getFirst().name());
    }

    /** 单图片 Skill 的参数就是用户原始描述，无需额外模型编排。 */
    private AgentState executeDirectImage(
            AgentState state,
            String task,
            ToolDefinition definition) throws Exception {
        JsonNode arguments = objectMapper.createObjectNode().put("prompt", task);
        ToolResult result = executeTool(state, new ToolCall(
                "image-" + UUID.randomUUID(), definition.name(), arguments));
        if (result.status() != ToolResultStatus.SUCCEEDED) {
            throw new IllegalStateException("图片生成工具执行失败\n" + result.errorStack());
        }
        JsonNode output = result.output();
        String dataUrl = requiredText(output, "dataUrl");
        String model = requiredText(output, "model");
        String response = "图片已生成。\n\n![生成图片](" + dataUrl + ")\n\n模型：`" + model + "`";
        return state
                .withMessage(ChatMessage.assistant(response))
                .withVariable(RESULT_KEY, objectMapper.writeValueAsString(output))
                .withVariable(RESPONSE_KEY, response)
                .withVariable(MODEL_KEY, model)
                .withVariable(PlannerNode.RESPONSE_KEY, response)
                .withVariable(PlannerNode.FINAL_RESPONSE_KEY, response)
                .withTraceEntry(NODE_NAME);
    }

    private String requiredText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException("图片工具结果缺少字段: " + field);
        }
        return value.textValue();
    }

    private void appendTaskIfMissing(List<ChatMessage> messages, String task) {
        if (!messages.isEmpty()) {
            ChatMessage last = messages.getLast();
            if (last.role() == ChatMessage.Role.USER
                    && last.content() instanceof ChatMessage.TextContent text
                    && task.equals(text.text())) {
                return;
            }
        }
        messages.add(ChatMessage.user(task));
    }

    private AgentState withSkillEvidence(
            AgentState state,
            SkillPromptContext skills) {
        if (skills == null) {
            return state;
        }
        String active = skills.activatedSkills().stream()
                .map(skill -> skill.name() + "@" + skill.version())
                .collect(java.util.stream.Collectors.joining(","));
        return state
                .withVariable(ACTIVE_SKILLS_KEY, active)
                .withVariable(SKILL_FINGERPRINT_KEY, skills.fingerprint());
    }

    private String userFacingFailure(Exception exception) {
        String message = exception.getMessage();
        if (message != null && message.startsWith("图片生成工具执行失败")) {
            return "图片生成服务已被真实调用，但上游返回失败。完整 HTTP 错误已写入工具审计，请稍后重试。";
        }
        return "工具调用失败，完整错误已写入工具审计。";
    }

    private List<ToolDefinition> exposedDefinitions(SkillPromptContext skills) {
        if (skills == null || skills.activatedSkills().isEmpty()) {
            return toolRegistry.list();
        }
        Set<String> names = skills.activatedSkills().stream()
                .flatMap(skill -> skill.tools().stream())
                .map(tool -> tool.name())
                .collect(java.util.stream.Collectors.toSet());
        return toolRegistry.list().stream().filter(tool -> names.contains(tool.name())).toList();
    }

    private String systemPrompt(
            SkillPromptContext skills,
            List<ToolDefinition> definitions,
            Map<String, String> protocolToRegistryName) {
        StringBuilder prompt = new StringBuilder("你是 Agent4J 通用工具节点。只能通过已注册工具完成任务，工具返回后再给出最终回答。\n可用工具：");
        definitions.forEach(tool -> prompt.append("\n- ")
                .append(protocolName(tool.name(), protocolToRegistryName))
                .append(": ").append(tool.description()));
        if (skills != null && !skills.activatedSkills().isEmpty()) {
            prompt.append("\n\n已激活 Skill：\n")
                    .append(normalizedActivationSection(skills, protocolToRegistryName));
        }
        return prompt.toString();
    }

    private String normalizedActivationSection(
            SkillPromptContext skills,
            Map<String, String> protocolToRegistryName) {
        StringBuilder section = new StringBuilder();
        for (var skill : skills.activatedSkills()) {
            section.append("## ").append(skill.name()).append('@').append(skill.version()).append('\n');
            section.append("tools:\n");
            for (SkillToolMetadata tool : skill.tools()) {
                section.append("- ").append(protocolName(tool.name(), protocolToRegistryName))
                        .append(": ").append(tool.description()).append("\n")
                        .append("  inputSchema: ").append(SkillPromptJson.canonicalJson(objectMapper, tool.inputSchema())).append('\n');
            }
            section.append("knowledge:\n").append(skill.promptFragment()).append('\n');
        }
        return section.toString();
    }

    private Map<String, String> toolNameMapping(List<ToolDefinition> definitions) {
        Map<String, String> mapping = new HashMap<>();
        Map<String, String> used = new HashMap<>();
        for (ToolDefinition definition : definitions) {
            String base = definition.name().replaceAll("[^A-Za-z0-9]", "_");
            if (base.isBlank()) {
                base = "tool";
            }
            base = base.length() > 64 ? base.substring(0, 64) : base;
            String protocolName = base;
            int suffix = 2;
            while (used.containsKey(protocolName)) {
                String suffixText = "_" + suffix++;
                int prefixLength = Math.max(1, 64 - suffixText.length());
                protocolName = base.substring(0, Math.min(base.length(), prefixLength)) + suffixText;
            }
            used.put(protocolName, definition.name());
            mapping.put(definition.name(), protocolName);
        }
        Map<String, String> reverse = new HashMap<>();
        mapping.forEach((registryName, protocolName) -> reverse.put(protocolName, registryName));
        return reverse;
    }

    private String protocolName(String registryName, Map<String, String> protocolToRegistryName) {
        return protocolToRegistryName.entrySet().stream()
                .filter(entry -> entry.getValue().equals(registryName))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("工具名称映射缺失: " + registryName));
    }

    private String messageText(ChatMessage message) {
        return message.content() instanceof ChatMessage.TextContent text ? text.text() : "";
    }

    private String required(AgentState state, String key) {
        String value = state.variables().get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少状态变量: " + key);
        return value;
    }

    private String stackTrace(Exception exception) {
        StringWriter writer = new StringWriter();
        exception.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
