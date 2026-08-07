package com.agent.core.intent;

import com.agent.core.llm.ChatMessage;
import com.agent.core.prompt.PromptCatalog;
import com.agent.core.prompt.RenderedPrompt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** 结合确定性动作识别和严格 JSON 语义结果的任务分类器。 */
public final class ModelIntentClassifier implements IntentClassifier {

    private static final String ROUTE_PROMPT_NAME = "planner.route";
    private static final String ROUTE_PROMPT_VERSION = "1";
    private static final Set<String> EXACT_FIELDS = Set.of(
            "route", "taskKind", "complexity", "requiredCapabilities", "reason");
    private static final List<String> CODE_ACTION_MARKERS = List.of(
            "修改", "改成", "写代码", "生成代码", "实现", "修复", "重构", "补充测试",
            "fix ", "implement ", "refactor ");
    private static final List<String> COMMAND_ACTION_MARKERS = List.of(
            "运行测试", "执行测试", "执行命令", "运行命令", "运行构建", "执行构建", "编译",
            "run tests", "run test", "execute command", "run command", "build project");
    private static final List<String> BROWSER_ACTION_MARKERS = List.of(
            "点击页面", "点击按钮", "打开网页", "导航到", "截取页面", "页面截图", "操作浏览器",
            "click page", "click button", "open page", "navigate to", "take screenshot");
    private static final List<String> PROJECT_REFERENCE_MARKERS = List.of(
            "当前项目", "这个项目", "本项目", "当前仓库", "这个仓库", "本仓库",
            "当前代码库", "这个代码库", "本代码库", "this project", "this repository",
            "this codebase");
    private static final List<String> EXPLANATION_MARKERS = List.of(
            "解释", "说明", "分析", "介绍", "为什么", "explain", "why");

    private final IntentModel model;
    private final ObjectMapper objectMapper;
    private final PromptCatalog promptCatalog;

    /** 创建模型端口、严格 JSON 解析器和 Prompt 目录均由构造器注入的分类器。 */
    public ModelIntentClassifier(
            IntentModel model,
            ObjectMapper objectMapper,
            PromptCatalog promptCatalog) {
        this.model = Objects.requireNonNull(model, "model 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.promptCatalog = Objects.requireNonNull(promptCatalog, "promptCatalog 不能为空");
    }

    /** 明确动作走确定性快路由，其余任务使用严格五字段 JSON 分类。 */
    @Override
    public TaskDecision classify(List<ChatMessage> history, String task) {
        List<ChatMessage> exactHistory = List.copyOf(Objects.requireNonNull(
                history, "history 不能为空"));
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("task 不能为空");
        }
        TaskDecision fastDecision = classifyExplicitAction(task);
        if (fastDecision != null) {
            return fastDecision;
        }
        TaskDecision knowledgeDecision = classifyProjectQuestion(task);
        if (knowledgeDecision != null) {
            return knowledgeDecision;
        }
        TaskDecision questionDecision = classifyDirectQuestion(task);
        if (questionDecision != null) {
            return questionDecision;
        }
        RenderedPrompt prompt = promptCatalog.render(
                ROUTE_PROMPT_NAME, ROUTE_PROMPT_VERSION, java.util.Map.of("task", task));
        List<ChatMessage> messages = new ArrayList<>(exactHistory.size() + 2);
        messages.add(ChatMessage.system(prompt.staticSection()));
        messages.addAll(exactHistory);
        messages.add(ChatMessage.user(prompt.dynamicSection()));
        String response = model.classify(List.copyOf(messages));
        return parseOrFallback(response);
    }

    private TaskDecision classifyExplicitAction(String task) {
        String normalized = task.toLowerCase(Locale.ROOT);
        boolean code = containsAny(normalized, CODE_ACTION_MARKERS);
        boolean command = containsAny(normalized, COMMAND_ACTION_MARKERS);
        boolean browser = containsAny(normalized, BROWSER_ACTION_MARKERS);
        if (!code && !command && !browser) {
            return null;
        }
        EnumSet<RequiredCapability> capabilities = EnumSet.noneOf(RequiredCapability.class);
        if (code) {
            capabilities.add(RequiredCapability.CODE_READ);
            capabilities.add(RequiredCapability.CODE_WRITE);
        }
        if (command) {
            capabilities.add(RequiredCapability.TERMINAL);
        }
        if (browser) {
            capabilities.add(RequiredCapability.BROWSER);
        }
        int actionKinds = (code ? 1 : 0) + (command ? 1 : 0) + (browser ? 1 : 0);
        boolean mixed = actionKinds > 1 || containsAny(normalized, EXPLANATION_MARKERS);
        TaskKind kind = mixed
                ? TaskKind.MIXED
                : code
                        ? TaskKind.CODE_CHANGE
                        : command ? TaskKind.COMMAND_EXECUTION : TaskKind.BROWSER_OPERATION;
        TaskComplexity complexity = mixed
                ? TaskComplexity.COMPLEX : TaskComplexity.STANDARD;
        return new TaskDecision(
                TaskRoute.AGENT,
                kind,
                complexity,
                capabilities,
                "检测到明确执行动作");
    }

    private TaskDecision classifyProjectQuestion(String task) {
        String normalized = task.toLowerCase(Locale.ROOT);
        if (!containsAny(normalized, PROJECT_REFERENCE_MARKERS)
                || !isQuestionOrExplanation(task, normalized)) {
            return null;
        }
        return new TaskDecision(
                TaskRoute.KNOWLEDGE,
                TaskKind.PROJECT_QUERY,
                TaskComplexity.STANDARD,
                Set.of(RequiredCapability.CODE_READ),
                "检测到当前项目只读知识问答");
    }

    private TaskDecision classifyDirectQuestion(String task) {
        String normalized = task.toLowerCase(Locale.ROOT);
        if (!isQuestionOrExplanation(task, normalized)) {
            return null;
        }
        return new TaskDecision(
                TaskRoute.CHAT,
                TaskKind.CHAT,
                TaskComplexity.SIMPLE,
                Set.of(),
                "检测到明确自然语言问答");
    }

    private boolean isQuestionOrExplanation(String task, String normalized) {
        return task.endsWith("?")
                || task.endsWith("？")
                || normalized.startsWith("what ")
                || normalized.startsWith("why ")
                || normalized.startsWith("how ")
                || task.startsWith("你是什么")
                || task.startsWith("什么是")
                || task.startsWith("为什么")
                || task.startsWith("如何")
                || task.startsWith("请解释")
                || task.startsWith("介绍");
    }

    private TaskDecision parseOrFallback(String response) {
        try {
            if (response == null || response.isBlank()) {
                throw new IllegalArgumentException("响应为空");
            }
            JsonNode root = objectMapper.readTree(response);
            if (!root.isObject()) {
                throw new IllegalArgumentException("响应不是 JSON 对象");
            }
            Set<String> actualFields = new HashSet<>();
            root.fieldNames().forEachRemaining(actualFields::add);
            if (!actualFields.equals(EXACT_FIELDS)) {
                throw new IllegalArgumentException("JSON 字段不精确");
            }
            TaskRoute route = enumValue(root.get("route"), TaskRoute.class, "route");
            TaskKind taskKind = enumValue(root.get("taskKind"), TaskKind.class, "taskKind");
            TaskComplexity complexity = enumValue(
                    root.get("complexity"), TaskComplexity.class, "complexity");
            JsonNode capabilityNode = root.get("requiredCapabilities");
            if (!capabilityNode.isArray()) {
                throw new IllegalArgumentException("requiredCapabilities 必须是数组");
            }
            EnumSet<RequiredCapability> capabilities = EnumSet.noneOf(
                    RequiredCapability.class);
            for (JsonNode value : capabilityNode) {
                capabilities.add(enumValue(
                        value, RequiredCapability.class, "requiredCapabilities 元素"));
            }
            String reason = text(root.get("reason"), "reason");
            return new TaskDecision(route, taskKind, complexity, capabilities, reason);
        } catch (Exception exception) {
            return new TaskDecision(
                    TaskRoute.CHAT,
                    TaskKind.CHAT,
                    TaskComplexity.SIMPLE,
                    Set.of(),
                    "语义路由结构不合法，执行无副作用问答回退");
        }
    }

    private <T extends Enum<T>> T enumValue(
            JsonNode node,
            Class<T> enumType,
            String field) {
        String value = text(node, field);
        return Enum.valueOf(enumType, value);
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.isTextual() || node.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " 必须是非空字符串");
        }
        return node.textValue();
    }

    private boolean containsAny(String normalized, List<String> markers) {
        return markers.stream().anyMatch(normalized::contains);
    }
}
