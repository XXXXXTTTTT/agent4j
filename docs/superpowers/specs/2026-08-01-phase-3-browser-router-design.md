# Phase 3 浏览器自动化、模型路由与审查节点设计

## 目标

在 Phase 2 的 `CoderNode -> OpsNode` 执行链基础上实现 Phase 3。交付内容包括：

- 基于 Playwright for Java 的异步浏览器服务，支持页面导航、精确选择器点击、
  完整 DOM 提取和完整页面 PNG 截图。
- 基于构造器注入的 `ModelRouter`，按照强类型 `TaskType` 选择主模型并通过
  Resilience4j 熔断器执行有序降级。
- 支持 OpenAI 兼容多模态消息，使截图像素能够真实传给视觉模型。
- `ReviewerNode`，将浏览器 DOM、PNG 截图和 `OpsNode` 测试证据合并为审查请求，
  并把结构化审查结果写回不可变 `AgentState`。

所有 Playwright 对象都在专属 Java 21 虚拟线程上创建、使用和关闭。所有失败都
保留原始 cause 或 suppressed exception；节点失败将完整 Java 堆栈写入状态，
供后续修复循环读取。

## 依赖与模块边界

依赖版本来自 2026-08-01 Maven Central `maven-metadata.xml` 的 `release`：

- `com.microsoft.playwright:playwright:1.61.0`
- `io.github.resilience4j:resilience4j-circuitbreaker:2.4.0`

根 POM 新增 `playwright.version` 与 `resilience4j.version` 属性。
`agent-sandbox` 依赖 Playwright，浏览器协议及实现位于
`com.agent.sandbox.browser`。`agent-core` 依赖 Resilience4j，并继续使用 Phase 2
已经建立的 `agent-core -> agent-sandbox` 依赖方向。

`agent-sandbox` 不依赖 `agent-core`。`ReviewerNode` 可以依赖浏览器协议与
`ModelRouter`，但浏览器服务不感知 `AgentState`、任务类型、模型或审查结果。
`ModelRouter` 不依赖 Spring，也不读取配置文件或环境变量。

## 浏览器公开协议

`com.agent.sandbox.browser.BrowserAutomation` 是异步浏览器协议：

```java
public interface BrowserAutomation extends AutoCloseable {
    CompletableFuture<NavigationResult> navigate(URI url, Duration timeout);

    CompletableFuture<Void> click(String selector, Duration timeout);

    CompletableFuture<String> extractDom();

    CompletableFuture<BrowserScreenshot> screenshot(Duration timeout);
}
```

协议使用以下不可变 record：

```java
public record NavigationResult(
        URI requestedUrl,
        URI finalUrl,
        OptionalInt statusCode) {
}

public record BrowserScreenshot(byte[] pngBytes, String mediaType) {
}
```

`NavigationResult.statusCode` 在 Playwright 导航返回 HTTP `Response` 时保存其状态
码；导航没有 HTTP 响应时为 `OptionalInt.empty()`。三个字段均不允许为 null。

`BrowserScreenshot.mediaType` 只接受精确值 `image/png`。构造器保存
`pngBytes` 的副本，访问器也返回副本，防止调用方修改内部字节。

URL 必须是绝对 `http` 或 `https` URI。timeout 必须大于零。selector 必须非空，
并原样传给 Playwright Locator，不进行大小写、名称、前后缀或选择器类型推断。
截图固定使用 `Page.ScreenshotOptions.setFullPage(true)` 与 PNG 格式。

## PlaywrightBrowserService

`com.agent.sandbox.browser.PlaywrightBrowserService` 实现 `BrowserAutomation`。
公开构造器精确为 `public PlaywrightBrowserService()`，构造时只创建执行器，
Playwright 资源在第一个浏览器操作提交后延迟初始化。
服务拥有通过以下方式创建的专属执行器：

```java
Executors.newSingleThreadExecutor(
        Thread.ofVirtual().name("playwright-browser-", 0).factory())
```

Playwright Java 对象不是线程安全对象。服务在该执行器的工作线程内延迟创建
`Playwright`、headless Chromium、`BrowserContext` 与单个 `Page`，后续导航、
点击、DOM、截图与清理任务也全部提交到同一执行器。这样既满足线程亲和要求，
也明确使用 Java 21 虚拟线程处理浏览器等待。

`navigate` 调用 `Page.navigate`，并使用请求中的 timeout。返回值记录请求 URI、
`Page.url()` 得到的最终 URI 和可选 HTTP 状态码。`click` 使用
`Page.locator(selector).click(...)`。`extractDom` 返回 `Page.content()`。
`screenshot` 返回 Playwright 产生的完整页面 PNG 字节，不在仓库内创建固定文件。

服务关闭时先在同一虚拟线程按 Page、BrowserContext、Browser、Playwright 的
顺序关闭资源，再关闭执行器。重复关闭无副作用。关闭后所有新操作都返回失败的
`CompletableFuture`。初始化、操作和清理失败统一封装为保留 cause 的
`BrowserAutomationException`；清理阶段的后续失败作为 suppressed exception
保留。

## 多模态 ChatMessage

现有 `ChatMessage` 的纯文本静态工厂、角色、工具调用和 JSON 字段保持兼容。
以下内容类型全部定义为 `ChatMessage` 的 public 内嵌类型，消息内容改为强类型
sealed 层次：

- `Content` 只允许 `TextContent` 与 `MultimodalContent`。
- `TextContent(String text)` 序列化为现有 JSON 字符串。
- `MultimodalContent(List<ContentPart> parts)` 序列化为 OpenAI 兼容数组。
- `ContentPart` 只允许 `TextPart` 与 `ImageUrlPart`。
- `TextPart(String text)` 序列化为 `{"type":"text","text":"..."}`。
- `ImageUrlPart(ImageUrl imageUrl)` 序列化为
  `{"type":"image_url","image_url":{...}}`。
- `ImageUrl(String url, ImageDetail detail)` 保存 Data URL 与图像细节。
- `ImageDetail` 精确包含 `AUTO`、`LOW`、`HIGH`，协议值分别为 `auto`、`low`、
  `high`。

`ChatMessage.system(String)`、`user(String)`、`assistant(String)`、
`assistantToolCalls(...)` 和 `tool(...)` 保持现有行为。新增的多模态用户消息工厂
精确为：

```java
public static ChatMessage userMultimodal(List<ContentPart> parts)
```

该方法要求 parts 非空，并冻结所有列表。Jackson 使用内容形状区分字符串、数组
与 null，不根据字段名称、大小写或不完整结构推断内容类型。

`ReviewerNode` 使用 `data:image/png;base64,` 加 RFC 4648 Base64 字节生成精确
Data URL，并使用 `ImageDetail.HIGH`。

## 模型路由公开类型

以下类型位于 `com.agent.core.llm`：

```java
public enum TaskType {
    CODE,
    VISION,
    QUICK_CLASSIFICATION
}

public record ModelEndpoint(
        String name,
        String model,
        LlmClient client,
        CircuitBreaker circuitBreaker) {
}

public record ModelRequest(
        List<ChatMessage> messages,
        List<LlmClient.Tool> tools,
        JsonNode toolChoice,
        Double temperature) {
}

public record RoutedCompletion(
        String endpointName,
        String model,
        LlmClient.ChatCompletionResponse response) {
}
```

`ModelEndpoint` 拒绝空 name、空 model 和 null client、circuitBreaker。
`ModelRequest` 拒绝 null messages 并冻结 messages 与 tools；tools 为 null 时转换为
空列表。`RoutedCompletion` 的三个字段均不能为空。

`ModelRouter` 的构造器接收精确类型：

```java
public ModelRouter(Map<TaskType, List<ModelEndpoint>> routes)
```

构造器要求 `CODE`、`VISION`、`QUICK_CLASSIFICATION` 三个键全部存在，且每个列表
均非空。路由 Map 和端点列表均复制为不可修改集合。列表第一项是主端点，后续项
是严格按顺序执行的降级端点。生产代码不固定供应商、基础地址、模型名称或降级
数量。

公开执行方法为：

```java
public RoutedCompletion complete(TaskType taskType, ModelRequest request)
```

Router 为当前端点构造包含其精确 model 的 `LlmClient.ChatCompletionRequest`，并在
注入的 `CircuitBreaker.executeSupplier` 内调用 `LlmClient.complete`。成功响应
必须包含至少一个 choice，且第一个 choice 必须包含非 null message；否则当前
端点按失败处理并进入降级。

`ModelRouter` 不拥有注入的 `LlmClient` 与 `CircuitBreaker`，不负责关闭或重置
它们，也不创建隐藏的默认熔断配置。

## 路由错误语义

HTTP、LLM 协议、空响应、空 choices、空 message 和 Resilience4j
`CallNotPermittedException` 都触发下一个端点。单个端点失败包装为
`ModelEndpointException`，异常消息包含精确 endpoint name 与 model，原始异常
作为 cause 保留。

所有端点失败后抛出 `ModelRoutingException`。它的消息包含精确 `TaskType`，每个
`ModelEndpointException` 按尝试顺序作为 suppressed exception 添加。Router 不
捕获 `Error`，避免掩盖 JVM 级故障。

非空正常响应不会因为模型业务答案为拒绝、否定或测试未通过而触发降级。熔断器
是否打开完全由调用方注入的 Resilience4j 配置与实际调用结果决定。

## ReviewerNode

`com.agent.core.nodes.ReviewerNode` 实现 Phase 1 的 `Node`，构造器注入：

```java
public ReviewerNode(
        BrowserAutomation browserAutomation,
        ModelRouter modelRouter,
        ObjectMapper objectMapper,
        Duration browserTimeout)
```

状态键定义为公开常量，精确值如下：

- `URL_KEY = "reviewer.url"`
- `APPROVED_KEY = "reviewer.approved"`
- `SUMMARY_KEY = "reviewer.summary"`
- `FEEDBACK_KEY = "reviewer.feedback"`
- `MODEL_KEY = "reviewer.model"`
- `ERROR_KEY = "reviewer.error"`

节点还读取 Phase 2 `OpsNode` 的公开状态键：

- `ops.exitCode`
- `ops.stdout`
- `ops.stderr`
- `ops.timedOut`
- `ops.error`

输入必须包含非空 `reviewer.url`。Ops 证据必须满足以下一种精确形态：

1. `ops.exitCode`、`ops.stdout`、`ops.stderr`、`ops.timedOut` 四个键全部存在；
2. 存在非空 `ops.error`。

节点依次执行：

1. 将 `reviewer.url` 解析为 URI，并用统一 `browserTimeout` 导航。
2. 提取完整 DOM 和完整页面 PNG 截图。
3. 构造文本证据，包含最终 URI、完整 DOM 与状态中实际存在的 Ops 字段和值。
4. 构造由 `TextPart` 和 `ImageUrlPart` 组成的多模态用户消息。
5. 用 `TaskType.VISION` 调用 `ModelRouter`。
6. 读取第一个 choice 的助手纯文本，并将完整文本解析为 `ReviewerDecision`。
7. 写入审查结果与实际模型名称，追加 trace `reviewer`。

`ReviewerDecision` 是节点内部使用的不可变 record，JSON 字段精确为：

```json
{
  "approved": true,
  "summary": "审查摘要",
  "feedback": "修复反馈"
}
```

模型输出必须是完整 JSON 对象。节点不移除 Markdown 代码围栏，不修正字段名，
不执行大小写或近似匹配。`approved` 必须是 boolean，`summary` 与 `feedback` 必须
是非 null 字符串；多余字段和不完整结构均按协议错误处理。解析时显式启用
Jackson `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES`，不依赖调用方传入
`ObjectMapper` 的全局默认值。

`approved=false`、测试非零退出和命令超时都是正常审查证据，不转成 Java 异常。
浏览器、路由、响应内容或 JSON 解析失败时，节点将完整 Java 堆栈写入
`reviewer.error` 并追加 trace `reviewer`。节点不关闭任何注入资源。

## 测试策略

实现严格遵循红、绿、重构循环：

- `ChatMessageTest` 验证现有纯文本与工具消息 JSON 不变，并验证多模态 text、
  image_url、detail、Data URL 的精确序列化和反序列化。
- `BrowserModelTest` 验证 URI、timeout、selector、媒体类型、不可变集合与截图字节
  防御性复制。
- `PlaywrightBrowserServiceTest` 启动绑定回环地址与随机端口的真实本地 HTTP
  服务，并启动真实 headless Chromium，验证导航、精确 selector 点击、点击后的
  DOM 变化、完整 DOM、PNG 文件头、超时、异步调用和关闭语义。
- `ModelRouterTest` 使用真实 `LlmClient` 与 `MockRestServiceServer`，验证三种
  `TaskType` 的精确路由、主端点失败后的顺序降级、熔断打开、已熔断端点跳过、
  不同任务的路由隔离、响应校验以及全部 suppressed exception。
- `ReviewerNodeTest` 使用可控 `BrowserAutomation` 和模拟 HTTP 模型端点，验证
  PNG Data URL、DOM 与 Ops 证据实际进入请求，并验证批准、拒绝、模型名、严格
  JSON 协议、输入错误和完整堆栈。
- `CoderOpsReviewerGraphTest` 执行
  `CoderNode -> OpsNode -> ReviewerNode -> END`，验证修改、测试与审查闭环，
  trace 精确为 `coder`、`ops`、`reviewer`。

当前开发环境的真实 Chromium 集成测试必须实际执行，不允许跳过。其他未安装
Playwright Chromium 的环境通过 JUnit assumption 明确跳过浏览器集成测试，普通
测试仍必须通过。测试使用 JUnit `@TempDir` 或内存数据，不在仓库创建固定截图、
浏览器配置或敏感文件。

最终验收必须：

- 使用 `JAVA_HOME=C:\Program Files\Java\jdk-21` 运行 `mvn clean verify`。
- 明确报告测试总数、失败数、错误数与跳过数。
- 单独证明真实 Chromium 测试已执行。
- 检查 Maven 依赖树不含 LangChain4j 或 LangGraph4j。
- 检查测试结束后没有由测试遗留的 Playwright、Chromium 或本地 HTTP 服务进程。
- 检查工作区、暂存区与根 `.gitignore`。

## Phase 3 边界

本阶段不实现 Spring 配置绑定、REST 或 WebSocket API、浏览器池、持久化浏览器
会话、自动选择器推断、OCR、固定模型供应商配置、流式 ModelRouter、Phase 4
Checkpointer、HITL、数据库或状态推送。

浏览器二进制安装属于开发与部署环境准备，不把 Chromium 二进制提交到 Git。
Playwright 测试产物位于 Maven `target/` 或 JUnit 临时目录，现有根 `.gitignore`
已经排除 `target/` 与 `tmp/`，无需新增仓库内运行产物目录。
