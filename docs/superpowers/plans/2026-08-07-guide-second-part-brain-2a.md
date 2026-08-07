# Agent 教程第二篇 2A：Prompt、Context 与 Intent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Agent4J 增加版本化 Prompt、token 感知上下文窗口和强类型任务决策，并把三者接入现有 Planner 问答/代码分流。

**Architecture:** `agent-core` 新增彼此独立的 `prompt`、`context` 和 `intent` 包。Prompt 负责可审计渲染，Context 负责确定性预算和摘要端口，Intent 负责把自然语言分类为精确 record；`PlannerNode` 只协调这些端口并保持现有状态图路由值兼容。

**Tech Stack:** Java 21 records/sealed types、Jackson 2、JUnit 5、AssertJ、现有 `ModelRouter` 与 Maven 多模块构建。

---

## 文件结构

- 新建 `agent-core/src/main/java/com/agent/core/prompt/PromptTemplate.java`：不可变 Prompt 定义。
- 新建 `agent-core/src/main/java/com/agent/core/prompt/RenderedPrompt.java`：分区渲染结果与 SHA-256 指纹。
- 新建 `agent-core/src/main/java/com/agent/core/prompt/PromptCatalog.java`：精确名称/版本注册与渲染。
- 新建 `agent-core/src/main/java/com/agent/core/context/TokenEstimator.java`：token 估算端口。
- 新建 `agent-core/src/main/java/com/agent/core/context/Utf8TokenEstimator.java`：确定性 UTF-8 估算实现。
- 新建 `agent-core/src/main/java/com/agent/core/context/ContextSummaryProvider.java`：被裁剪历史的摘要端口。
- 新建 `agent-core/src/main/java/com/agent/core/context/ContextBudgetExceededException.java`：受保护消息超预算异常。
- 新建 `agent-core/src/main/java/com/agent/core/context/ContextWindowRequest.java`：上下文预算输入。
- 新建 `agent-core/src/main/java/com/agent/core/context/ContextWindow.java`：上下文预算结果。
- 新建 `agent-core/src/main/java/com/agent/core/context/ContextWindowManager.java`：优先级裁剪和摘要编排。
- 新建 `agent-core/src/main/java/com/agent/core/intent/TaskRoute.java`、`TaskKind.java`、`TaskComplexity.java`、`RequiredCapability.java`：精确枚举。
- 新建 `agent-core/src/main/java/com/agent/core/intent/TaskDecision.java`：强类型任务决策。
- 新建 `agent-core/src/main/java/com/agent/core/intent/IntentClassifier.java`：分类端口。
- 新建 `agent-core/src/main/java/com/agent/core/intent/IntentModel.java`：语义分类文本端口。
- 新建 `agent-core/src/main/java/com/agent/core/intent/ModelRouterIntentModel.java`：`ModelRouter` 适配器。
- 新建 `agent-core/src/main/java/com/agent/core/intent/ModelIntentClassifier.java`：确定性快路由和严格 JSON 语义路由。
- 新建 `agent-core/src/main/java/com/agent/core/nodes/PlannerPromptTemplates.java`：Planner 三类版本化模板。
- 修改 `agent-core/src/main/java/com/agent/core/nodes/PlannerNode.java`：接入 Prompt、Context、Intent 并记录审计状态。
- 修改 `agent-web/src/main/java/com/agent/web/config/ProductionAgentProperties.java`：增加 Planner 上下文 token 上限。
- 修改 `agent-web/src/main/java/com/agent/web/config/ProductionGraphConfiguration.java`：装配默认实现。
- 修改 `agent-web/src/main/resources/application.properties` 与 `.env.example`：公开精确配置。

### Task 1: 版本化 Prompt Catalog

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/prompt/PromptTemplate.java`
- Create: `agent-core/src/main/java/com/agent/core/prompt/RenderedPrompt.java`
- Create: `agent-core/src/main/java/com/agent/core/prompt/PromptCatalog.java`
- Test: `agent-core/src/test/java/com/agent/core/prompt/PromptCatalogTest.java`

- [ ] **Step 1: 写 Prompt Catalog 失败测试**

```java
package com.agent.core.prompt;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptCatalogTest {

    @Test
    void rendersStaticAndDynamicSectionsWithStableFingerprint() {
        PromptCatalog catalog = new PromptCatalog(List.of(new PromptTemplate(
                "planner.chat", "1", "只回答问题。", "当前问题：{{task}}", Set.of("task"))));

        RenderedPrompt first = catalog.render("planner.chat", "1", Map.of("task", "你是谁"));
        RenderedPrompt second = catalog.render("planner.chat", "1", Map.of("task", "你是谁"));

        assertThat(first.staticSection()).isEqualTo("只回答问题。");
        assertThat(first.dynamicSection()).isEqualTo("当前问题：你是谁");
        assertThat(first.fingerprint()).matches("[0-9a-f]{64}").isEqualTo(second.fingerprint());
    }

    @Test
    void rejectsMissingVariableAndUnknownVersion() {
        PromptCatalog catalog = new PromptCatalog(List.of(new PromptTemplate(
                "planner.chat", "1", "系统", "{{task}}", Set.of("task"))));

        assertThatThrownBy(() -> catalog.render("planner.chat", "1", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("task");
        assertThatThrownBy(() -> catalog.render("planner.chat", "2", Map.of("task", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("planner.chat@2");
    }
}
```

- [ ] **Step 2: 运行测试并确认因类型不存在而失败**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -pl agent-core -am -Dtest=PromptCatalogTest -DfailIfNoTests=false test
```

Expected: `COMPILATION ERROR`，明确报告 `PromptCatalog`、`PromptTemplate` 或 `RenderedPrompt` 不存在。

- [ ] **Step 3: 实现不可变 Prompt 类型和精确渲染**

`PromptTemplate` 使用 `{{name}}` 作为唯一变量语法；构造时验证 requiredVariables 中每一项恰好出现在 dynamicTemplate，`PromptCatalog` 拒绝重复 `name@version`。`render` 逐项替换必需变量，指纹输入固定为：

```text
name\nversion\nstaticSection\nrenderedDynamicSection
```

公开 API 精确为：

```java
public record PromptTemplate(
        String name,
        String version,
        String staticSection,
        String dynamicTemplate,
        Set<String> requiredVariables) { }

public record RenderedPrompt(
        String name,
        String version,
        String staticSection,
        String dynamicSection,
        String fingerprint) {
    public String combined() {
        return staticSection + "\n\n" + dynamicSection;
    }
}

public final class PromptCatalog {
    public PromptCatalog(List<PromptTemplate> templates) { }
    public RenderedPrompt render(String name, String version, Map<String, String> variables) { }
}
```

- [ ] **Step 4: 运行 Prompt 测试并确认通过**

Run: `mvn -pl agent-core -am -Dtest=PromptCatalogTest -DfailIfNoTests=false test`

Expected: `Tests run: 2, Failures: 0, Errors: 0`。

- [ ] **Step 5: 原子提交 Prompt Catalog**

```powershell
git -c safe.directory=D:/agent4j add -- agent-core/src/main/java/com/agent/core/prompt agent-core/src/test/java/com/agent/core/prompt
git -c safe.directory=D:/agent4j diff --cached --check
git -c safe.directory=D:/agent4j commit -m "feat(prompt): add versioned prompt catalog"
```

### Task 2: token 感知上下文窗口

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/context/TokenEstimator.java`
- Create: `agent-core/src/main/java/com/agent/core/context/Utf8TokenEstimator.java`
- Create: `agent-core/src/main/java/com/agent/core/context/ContextSummaryProvider.java`
- Create: `agent-core/src/main/java/com/agent/core/context/ContextBudgetExceededException.java`
- Create: `agent-core/src/main/java/com/agent/core/context/ContextWindowRequest.java`
- Create: `agent-core/src/main/java/com/agent/core/context/ContextWindow.java`
- Create: `agent-core/src/main/java/com/agent/core/context/ContextWindowManager.java`
- Test: `agent-core/src/test/java/com/agent/core/context/ContextWindowManagerTest.java`

- [ ] **Step 1: 写受保护消息和摘要的失败测试**

```java
package com.agent.core.context;

import com.agent.core.llm.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextWindowManagerTest {

    private final TokenEstimator estimator = message -> switch (message.role()) {
        case SYSTEM -> 2;
        case USER, ASSISTANT -> 3;
        case TOOL -> 4;
    };

    @Test
    void preservesSystemCurrentUserAndLatestToolErrorWhileSummarizingOldHistory() {
        ContextWindowManager manager = new ContextWindowManager(
                estimator, (messages, limit) -> "旧对话摘要");
        ContextWindow result = manager.fit(new ContextWindowRequest(
                ChatMessage.system("系统"),
                List.of(ChatMessage.user("旧问题"), ChatMessage.assistant("旧回答"),
                        ChatMessage.user("新问题"), ChatMessage.assistant("新回答")),
                ChatMessage.user("当前问题"),
                ChatMessage.tool("tool-1", "最近工具错误"),
                15,
                3));

        assertThat(result.messages()).contains(ChatMessage.system("系统"));
        assertThat(result.messages()).contains(ChatMessage.user("当前问题"));
        assertThat(result.messages()).contains(ChatMessage.tool("tool-1", "最近工具错误"));
        assertThat(result.messages()).anyMatch(message ->
                message.role() == ChatMessage.Role.SYSTEM
                        && ((ChatMessage.TextContent) message.content()).text().contains("旧对话摘要"));
        assertThat(result.droppedMessages()).isPositive();
        assertThat(result.summarized()).isTrue();
        assertThat(result.estimatedTokens()).isLessThanOrEqualTo(15);
    }

    @Test
    void rejectsBudgetSmallerThanProtectedMessages() {
        ContextWindowManager manager = new ContextWindowManager(estimator, (messages, limit) -> "");
        assertThatThrownBy(() -> manager.fit(new ContextWindowRequest(
                ChatMessage.system("系统"), List.of(), ChatMessage.user("当前"),
                ChatMessage.tool("tool-1", "错误"), 8, 0)))
                .isInstanceOf(ContextBudgetExceededException.class)
                .hasMessageContaining("受保护消息");
    }
}
```

- [ ] **Step 2: 运行测试并确认因上下文类型不存在而失败**

Run: `mvn -pl agent-core -am -Dtest=ContextWindowManagerTest -DfailIfNoTests=false test`

Expected: `COMPILATION ERROR`，明确报告 `ContextWindowManager` 或请求/结果类型不存在。

- [ ] **Step 3: 实现确定性上下文算法**

公开 API 精确为：

```java
@FunctionalInterface
public interface TokenEstimator {
    int estimate(ChatMessage message);
}

@FunctionalInterface
public interface ContextSummaryProvider {
    String summarize(List<ChatMessage> messages, int maxTokens);
}

public record ContextWindowRequest(
        ChatMessage systemMessage,
        List<ChatMessage> history,
        ChatMessage currentUserMessage,
        ChatMessage latestToolError,
        int maxInputTokens,
        int summaryMaxTokens) { }

public record ContextWindow(
        List<ChatMessage> messages,
        int estimatedTokens,
        int droppedMessages,
        boolean summarized) { }

public final class ContextWindowManager {
    public ContextWindowManager(TokenEstimator estimator, ContextSummaryProvider summaryProvider) { }
    public ContextWindow fit(ContextWindowRequest request) { }
}
```

算法顺序固定为：系统消息 → 被裁剪历史摘要 → 保留历史 → 最新工具错误 → 当前用户消息。先计算系统、工具错误和当前用户三类受保护消息；超限直接抛 `ContextBudgetExceededException`。当完整历史不能放入剩余预算时，先从剩余预算预留 `summaryMaxTokens`，再从最新消息向前保留历史；被裁剪消息交给摘要端口，摘要只有在实际估算 token 能放入预留预算时加入。`Utf8TokenEstimator` 对消息序列化文本使用 `ceil(UTF-8 字节数 / 4.0) + 4`，并将工具调用名称和参数计入估算。

- [ ] **Step 4: 运行上下文测试并确认通过**

Run: `mvn -pl agent-core -am -Dtest=ContextWindowManagerTest -DfailIfNoTests=false test`

Expected: `Tests run: 2, Failures: 0, Errors: 0`。

- [ ] **Step 5: 原子提交上下文窗口**

```powershell
git -c safe.directory=D:/agent4j add -- agent-core/src/main/java/com/agent/core/context agent-core/src/test/java/com/agent/core/context
git -c safe.directory=D:/agent4j diff --cached --check
git -c safe.directory=D:/agent4j commit -m "feat(context): add token aware context windows"
```

### Task 3: 强类型任务决策

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/intent/TaskRoute.java`
- Create: `agent-core/src/main/java/com/agent/core/intent/TaskKind.java`
- Create: `agent-core/src/main/java/com/agent/core/intent/TaskComplexity.java`
- Create: `agent-core/src/main/java/com/agent/core/intent/RequiredCapability.java`
- Create: `agent-core/src/main/java/com/agent/core/intent/TaskDecision.java`
- Create: `agent-core/src/main/java/com/agent/core/intent/IntentClassifier.java`
- Create: `agent-core/src/main/java/com/agent/core/intent/IntentModel.java`
- Create: `agent-core/src/main/java/com/agent/core/intent/ModelRouterIntentModel.java`
- Create: `agent-core/src/main/java/com/agent/core/intent/ModelIntentClassifier.java`
- Test: `agent-core/src/test/java/com/agent/core/intent/ModelIntentClassifierTest.java`

- [ ] **Step 1: 写快路由、复合意图与不合法输出回退测试**

```java
@Test
void routesExplicitCodeChangeWithoutSemanticModelCall() {
    ModelIntentClassifier classifier = classifierThatMustNotCallModel();
    TaskDecision decision = classifier.classify(List.of(), "解释这个类并修改 README.md");

    assertThat(decision.route()).isEqualTo(TaskRoute.AGENT);
    assertThat(decision.taskKind()).isEqualTo(TaskKind.MIXED);
    assertThat(decision.requiredCapabilities()).containsExactlyInAnyOrder(
            RequiredCapability.CODE_READ, RequiredCapability.CODE_WRITE);
}

@Test
void parsesExactSemanticDecisionJson() {
    ModelIntentClassifier classifier = classifierReturning("""
            {"route":"CHAT","taskKind":"CHAT","complexity":"SIMPLE",
             "requiredCapabilities":[],"reason":"无需工具"}
            """);
    assertThat(classifier.classify(List.of(), "按天气规划"))
            .isEqualTo(new TaskDecision(
                    TaskRoute.CHAT, TaskKind.CHAT, TaskComplexity.SIMPLE,
                    Set.of(), "无需工具"));
}

@Test
void fallsBackToSideEffectFreeChatWhenSemanticJsonIsInvalid() {
    ModelIntentClassifier classifier = classifierReturning("agent");
    TaskDecision decision = classifier.classify(List.of(), "接着说");
    assertThat(decision.route()).isEqualTo(TaskRoute.CHAT);
    assertThat(decision.requiredCapabilities()).isEmpty();
    assertThat(decision.reason()).contains("结构不合法");
}

private ModelIntentClassifier classifierThatMustNotCallModel() {
    return classifier(messages -> {
        throw new AssertionError("明确代码动作不应调用语义模型");
    });
}

private ModelIntentClassifier classifierReturning(String response) {
    return classifier(messages -> response);
}

private ModelIntentClassifier classifier(IntentModel model) {
    PromptCatalog catalog = new PromptCatalog(List.of(new PromptTemplate(
            "planner.route", "1", "只输出严格 JSON。", "任务：{{task}}", Set.of("task"))));
    return new ModelIntentClassifier(model, new ObjectMapper(), catalog);
}
```

- [ ] **Step 2: 运行测试并确认因 intent 类型不存在而失败**

Run: `mvn -pl agent-core -am -Dtest=ModelIntentClassifierTest -DfailIfNoTests=false test`

Expected: `COMPILATION ERROR`，明确报告 `TaskDecision` 或 `ModelIntentClassifier` 不存在。

- [ ] **Step 3: 实现精确枚举、record 与分类器**

```java
public enum TaskRoute { CHAT, AGENT }
public enum TaskKind { CHAT, CODE_CHANGE, COMMAND_EXECUTION, BROWSER_OPERATION, MIXED }
public enum TaskComplexity { SIMPLE, STANDARD, COMPLEX }
public enum RequiredCapability { CODE_READ, CODE_WRITE, TERMINAL, BROWSER }

public record TaskDecision(
        TaskRoute route,
        TaskKind taskKind,
        TaskComplexity complexity,
        Set<RequiredCapability> requiredCapabilities,
        String reason) { }

@FunctionalInterface
public interface IntentClassifier {
    TaskDecision classify(List<ChatMessage> history, String task);
}

@FunctionalInterface
public interface IntentModel {
    String classify(List<ChatMessage> messages);
}

public final class ModelRouterIntentModel implements IntentModel {
    private final ModelRouter modelRouter;

    public ModelRouterIntentModel(ModelRouter modelRouter) {
        this.modelRouter = Objects.requireNonNull(modelRouter, "modelRouter 不能为空");
    }

    @Override
    public String classify(List<ChatMessage> messages) {
        RoutedCompletion completion = modelRouter.complete(
                TaskType.QUICK_CLASSIFICATION,
                new ModelRequest(messages, List.of(), null, 0.0));
        ChatMessage message = completion.response().choices().getFirst().message();
        if (!(message.content() instanceof ChatMessage.TextContent textContent)) {
            throw new IllegalStateException("任务路由模型响应 content 必须是 TextContent");
        }
        return textContent.text();
    }
}
```

`ModelIntentClassifier` 的构造器精确为 `ModelIntentClassifier(IntentModel, ObjectMapper, PromptCatalog)`。它先用现有明确代码/命令/浏览器动作词做无模型快路由；一个请求同时包含解释与动作时返回 `MIXED`。其余请求渲染 `planner.route@1` 后调用 `IntentModel`，只接受恰好包含 `route`、`taskKind`、`complexity`、`requiredCapabilities`、`reason` 五个字段的 JSON 对象。`ModelRouterIntentModel` 负责使用 `TaskType.QUICK_CLASSIFICATION` 调用现有 `ModelRouter` 并提取文本。任何字段缺失、未知枚举、多余字段或能力与 route 冲突都返回 `CHAT/CHAT/SIMPLE/空能力集` 的无副作用决策，并在 reason 中记录“结构不合法”；网络和 HTTP 异常继续抛出，由 Planner 保存完整堆栈。

- [ ] **Step 4: 运行 Intent 测试与既有 Planner 路由回归**

Run:

```powershell
mvn -pl agent-core -am -Dtest=ModelIntentClassifierTest,PlannerNodeTest -DfailIfNoTests=false test
```

Expected: 新增测试通过；Planner 旧测试此时仍通过，因为尚未切换实现。

- [ ] **Step 5: 原子提交强类型 Intent**

```powershell
git -c safe.directory=D:/agent4j add -- agent-core/src/main/java/com/agent/core/intent agent-core/src/test/java/com/agent/core/intent
git -c safe.directory=D:/agent4j diff --cached --check
git -c safe.directory=D:/agent4j commit -m "feat(intent): add typed task decisions"
```

### Task 4: Planner 接入 Prompt、Context 与 Intent

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/nodes/PlannerPromptTemplates.java`
- Modify: `agent-core/src/main/java/com/agent/core/nodes/PlannerNode.java`
- Modify: `agent-core/src/test/java/com/agent/core/nodes/PlannerNodeTest.java`

- [ ] **Step 1: 增加 Planner 审计状态和长上下文失败测试**

新增测试必须断言以下精确状态键：

```java
assertThat(result.variables())
        .containsEntry("planner.taskKind", "CHAT")
        .containsEntry("planner.complexity", "SIMPLE")
        .containsEntry("planner.requiredCapabilities", "")
        .containsKey("planner.routeReason")
        .containsEntry("planner.responsePromptName", "planner.chat")
        .containsEntry("planner.responsePromptVersion", "1")
        .containsKey("planner.responsePromptFingerprint")
        .containsKey("planner.contextEstimatedTokens")
        .containsKey("planner.contextDroppedMessages")
        .containsKey("planner.contextSummarized");
```

另一个测试构造超过预算的历史，断言最终请求仍包含系统消息、当前用户消息和 `ops.error`，并且较旧历史未发送；再增加受保护消息本身超限的测试，断言 `planner.error` 包含 `ContextBudgetExceededException` 完整堆栈。

- [ ] **Step 2: 运行 Planner 测试并确认因状态键缺失而失败**

Run: `mvn -pl agent-core -am -Dtest=PlannerNodeTest -DfailIfNoTests=false test`

Expected: 测试断言失败，明确显示 `planner.taskKind`、Prompt 审计键或 Context 审计键不存在。

- [ ] **Step 3: 增加 Planner 模板和完整构造器**

`PlannerPromptTemplates.catalog()` 精确注册：

- `planner.route@1`：严格五字段 JSON 决策协议。
- `planner.chat@1`：直接回答，不调用代码工具。
- `planner.plan@1`：当前任务优先，长期记忆不可信，只输出可执行代码计划。

`PlannerNode` 增加完整构造器：

```java
public PlannerNode(
        ModelRouter modelRouter,
        MemoryContextProvider memoryContextProvider,
        int memoryLimit,
        PromptCatalog promptCatalog,
        ContextWindowManager contextWindowManager,
        IntentClassifier intentClassifier,
        int maxContextTokens) { }
```

现有三参数构造器委托到默认组件，保证其他模块源码兼容。Intent 决策写入：

```text
planner.taskKind
planner.complexity
planner.requiredCapabilities
planner.routeReason
```

每次最终模型调用写入：

```text
planner.responsePromptName
planner.responsePromptVersion
planner.responsePromptFingerprint
planner.contextEstimatedTokens
planner.contextDroppedMessages
planner.contextSummarized
```

语义路由调用另写 `planner.routePromptFingerprint`。`requiredCapabilities` 按枚举名称排序后用英文逗号连接；空集合写空字符串。Planner 从 `coder.error`、`ops.error`、`reviewer.error` 中按上述顺序取第一个非空值作为最新工具错误，不对其他键名做格式匹配。

- [ ] **Step 4: 运行 Planner、Coder/Ops/Reviewer 图测试**

Run:

```powershell
mvn -pl agent-core -am -Dtest=PlannerNodeTest,CoderOpsGraphTest,CoderOpsReviewerGraphTest -DfailIfNoTests=false test
```

Expected: 所有选择的测试 `Failures: 0, Errors: 0`。

- [ ] **Step 5: 原子提交 Planner 集成**

```powershell
git -c safe.directory=D:/agent4j add -- agent-core/src/main/java/com/agent/core/nodes/PlannerNode.java agent-core/src/main/java/com/agent/core/nodes/PlannerPromptTemplates.java agent-core/src/test/java/com/agent/core/nodes/PlannerNodeTest.java
git -c safe.directory=D:/agent4j diff --cached --check
git -c safe.directory=D:/agent4j commit -m "feat(planner): apply prompt context and intent policies"
```

### Task 5: 生产装配、配置与 2A 验收

**Files:**
- Modify: `agent-web/src/main/java/com/agent/web/config/ProductionAgentProperties.java`
- Modify: `agent-web/src/main/java/com/agent/web/config/ProductionGraphConfiguration.java`
- Modify: `agent-web/src/main/resources/application.properties`
- Modify: `.env.example`
- Modify: `agent-web/src/test/java/com/agent/web/config/ProductionGraphConfigurationTest.java`
- Modify: `agent-web/src/test/java/com/agent/web/config/ProductionCodeAgentIntegrationTest.java`
- Create: `agent-web/src/test/java/com/agent/web/config/ProductionAgentPropertiesTest.java`
- Modify: `agent-eval/src/test/java/com/agent/eval/LlmEddTest.java`
- Modify: `docs/ENGINEERING_PITFALLS.md`

- [ ] **Step 1: 写生产配置失败测试**

将 `ProductionGraphConfigurationTest` 和 `ProductionCodeAgentIntegrationTest` 的构造数据加入 `plannerContextMaxTokens=12_000`。新建 `ProductionAgentPropertiesTest`，复制完整有效参数并只将最后一个 `plannerContextMaxTokens` 参数设为 0：

```java
assertThatThrownBy(() -> new ProductionAgentProperties(
        true, Path.of("."), "repo", "user", "", "DOCKER", "/bin/bash",
        "python:3.12-slim", "/workspace", "", "", Duration.ofMinutes(1),
        Duration.ofSeconds(30), 200, 524_288, 2, 12, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("plannerContextMaxTokens");
```

扩充 EDD 结果断言，要求成功场景状态包含 `planner.taskKind`、`planner.complexity`、`planner.responsePromptFingerprint` 和 `planner.contextEstimatedTokens`。

- [ ] **Step 2: 运行 Web 配置测试并确认构造器签名/断言失败**

Run:

```powershell
mvn -pl agent-web -am -Dfrontend.skip=true -Dtest=ProductionAgentPropertiesTest,ProductionGraphConfigurationTest,ProductionCodeAgentIntegrationTest -DfailIfNoTests=false test
```

Expected: `COMPILATION ERROR` 或断言失败，原因是 `plannerContextMaxTokens` 尚未存在。

- [ ] **Step 3: 装配默认策略并公开精确配置**

在 `ProductionAgentProperties` 增加 `int plannerContextMaxTokens`，要求大于 0；`application.properties` 增加：

```properties
agent.production.planner-context-max-tokens=${AGENT_CODE_PLANNER_CONTEXT_MAX_TOKENS:12000}
```

`.env.example` 增加：

```dotenv
AGENT_CODE_PLANNER_CONTEXT_MAX_TOKENS=12000
```

`ProductionGraphConfiguration` 构造 `PromptCatalog`、`Utf8TokenEstimator`、`ContextWindowManager` 和 `ModelIntentClassifier` 后注入 Planner。默认摘要端口只对被裁剪文本生成确定性、带角色标签且受 `summaryMaxTokens` 限制的摘录，不发起额外模型请求，避免问答 TTFT 退化。

- [ ] **Step 4: 更新工程复盘并执行模块验证**

在 `docs/ENGINEERING_PITFALLS.md` 第二篇相关位置记录：自由文本路由导致连续追问误入代码链、字符窗口对中英文 token 失真、Prompt 硬编码无法审计；按“问题现象→根因→代码级解决方案”描述本次实现。

Run:

```powershell
mvn -pl agent-core,agent-web,agent-eval -am -Dfrontend.skip=true test
```

Expected: Maven `BUILD SUCCESS`，所有实际运行测试 `Failures: 0, Errors: 0`；外部 EDD 未开启时明确跳过。

- [ ] **Step 5: 在已配置真实模型时运行 2A EDD**

Run:

```powershell
mvn -pl agent-eval -am -Dtest=LlmEddTest -DfailIfNoTests=false test
```

Expected: `AGENT_LLM_ENABLED=true` 时实际调用配置端点，模型身份、连续出游追问、代码修改和不合法路由场景通过并生成 `agent-eval/target/edd/` 报告；未开启时 JUnit assumption 明确跳过。

- [ ] **Step 6: 执行完整构建并提交 2A 生产装配**

Run:

```powershell
mvn clean package -DskipTests -Dfrontend.skip=true
git -c safe.directory=D:/agent4j status --short
git -c safe.directory=D:/agent4j diff --check
```

Expected: Maven `BUILD SUCCESS`，Git 只包含本任务列出的配置、测试、EDD 和文档文件。

```powershell
git -c safe.directory=D:/agent4j add -- .env.example agent-web/src/main/java/com/agent/web/config/ProductionAgentProperties.java agent-web/src/main/java/com/agent/web/config/ProductionGraphConfiguration.java agent-web/src/main/resources/application.properties agent-web/src/test/java/com/agent/web/config/ProductionAgentPropertiesTest.java agent-web/src/test/java/com/agent/web/config/ProductionGraphConfigurationTest.java agent-web/src/test/java/com/agent/web/config/ProductionCodeAgentIntegrationTest.java agent-eval/src/test/java/com/agent/eval/LlmEddTest.java docs/ENGINEERING_PITFALLS.md
git -c safe.directory=D:/agent4j diff --cached --check
git -c safe.directory=D:/agent4j commit -m "feat(agent): wire brain policies into production"
```
