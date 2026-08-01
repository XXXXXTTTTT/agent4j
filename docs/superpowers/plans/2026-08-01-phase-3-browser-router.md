# Phase 3 Browser Router Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付真实 Playwright 浏览器自动化、支持 OpenAI 多模态内容的构造器注入式 ModelRouter，以及将页面证据与测试日志合并审查的 ReviewerNode。

**Architecture:** `agent-sandbox` 在专属单虚拟线程上管理 Playwright 与 Chromium，并通过异步 `BrowserAutomation` 协议暴露导航、点击、DOM 和 PNG 截图。`agent-core` 扩展强类型多模态消息，使用注入的 `Map<TaskType, List<ModelEndpoint>>` 和独立 Resilience4j 熔断器执行模型降级；`ReviewerNode` 只依赖浏览器协议和 Router，并将严格 JSON 审查结果写回不可变状态。

**Tech Stack:** Java 21、Spring Boot 3.3.13、Playwright for Java 1.61.0、Resilience4j CircuitBreaker 2.4.0、Jackson、JUnit 5、AssertJ、Spring MockRestServiceServer、Maven 3.8.8。

---

## 文件结构

- `pom.xml`：集中保存 Playwright 与 Resilience4j 精确版本。
- `agent-sandbox/pom.xml`：增加 Playwright 编译依赖。
- `agent-core/pom.xml`：增加 Resilience4j CircuitBreaker 编译依赖。
- `agent-core/src/main/java/com/agent/core/llm/ChatMessage.java`：纯文本、工具消息和强类型多模态消息。
- `agent-core/src/main/java/com/agent/core/llm/ChatMessageContentSerializer.java`：按照 OpenAI 形状序列化字符串或内容块数组。
- `agent-core/src/main/java/com/agent/core/llm/ChatMessageContentDeserializer.java`：严格解析字符串、数组或 null 内容。
- `agent-core/src/main/java/com/agent/core/llm/TaskType.java`：三个精确任务类型。
- `agent-core/src/main/java/com/agent/core/llm/ModelEndpoint.java`：模型、客户端与熔断器绑定。
- `agent-core/src/main/java/com/agent/core/llm/ModelRequest.java`：不含模型名的路由请求。
- `agent-core/src/main/java/com/agent/core/llm/RoutedCompletion.java`：实际端点、模型与响应。
- `agent-core/src/main/java/com/agent/core/llm/ModelEndpointException.java`：单个端点失败。
- `agent-core/src/main/java/com/agent/core/llm/ModelRoutingException.java`：整条降级链失败。
- `agent-core/src/main/java/com/agent/core/llm/ModelRouter.java`：精确任务路由、熔断和顺序降级。
- `agent-sandbox/src/main/java/com/agent/sandbox/browser/BrowserAutomation.java`：异步浏览器协议。
- `agent-sandbox/src/main/java/com/agent/sandbox/browser/NavigationResult.java`：导航结果。
- `agent-sandbox/src/main/java/com/agent/sandbox/browser/BrowserScreenshot.java`：防御性复制的 PNG 结果。
- `agent-sandbox/src/main/java/com/agent/sandbox/browser/BrowserAutomationException.java`：浏览器强类型异常。
- `agent-sandbox/src/main/java/com/agent/sandbox/browser/PlaywrightBrowserService.java`：线程亲和的真实 Playwright 实现。
- `agent-core/src/main/java/com/agent/core/nodes/ReviewerNode.java`：多模态审查状态节点。
- `agent-core/src/test/java/com/agent/core/llm/*`：多模态消息与模型路由测试。
- `agent-sandbox/src/test/java/com/agent/sandbox/browser/*`：浏览器模型和真实 Chromium 测试。
- `agent-core/src/test/java/com/agent/core/nodes/*`：ReviewerNode 与三节点图闭环测试。

所有 Maven 命令先在当前 PowerShell 会话设置：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
```

### Task 1: Phase 3 依赖边界

**Files:**
- Modify: `pom.xml`
- Modify: `agent-sandbox/pom.xml`
- Modify: `agent-core/pom.xml`

- [ ] **Step 1: 增加精确版本和模块依赖**

父 POM properties 增加：

```xml
<playwright.version>1.61.0</playwright.version>
<resilience4j.version>2.4.0</resilience4j.version>
```

`agent-sandbox/pom.xml` 增加：

```xml
<dependency>
    <groupId>com.microsoft.playwright</groupId>
    <artifactId>playwright</artifactId>
    <version>${playwright.version}</version>
</dependency>
```

`agent-core/pom.xml` 增加：

```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-circuitbreaker</artifactId>
    <version>${resilience4j.version}</version>
</dependency>
```

- [ ] **Step 2: 验证依赖解析和 Java 版本**

Run: `mvn -pl agent-core,agent-sandbox -am -DskipTests compile`

Expected: reactor 五个模块全部编译成功，编译 release 为 21。

- [ ] **Step 3: 检查依赖树**

Run: `mvn -pl agent-core,agent-sandbox -am dependency:tree`

Expected: 输出包含 `playwright:1.61.0` 与 `resilience4j-circuitbreaker:2.4.0`，不含 `langchain4j` 或 `langgraph4j`。

- [ ] **Step 4: 提交依赖变更**

```text
build(phase3): 集成浏览器与熔断依赖
```

### Task 2: OpenAI 强类型多模态消息

**Files:**
- Modify: `agent-core/src/test/java/com/agent/core/llm/ChatMessageTest.java`
- Modify: `agent-core/src/main/java/com/agent/core/llm/ChatMessage.java`
- Create: `agent-core/src/main/java/com/agent/core/llm/ChatMessageContentSerializer.java`
- Create: `agent-core/src/main/java/com/agent/core/llm/ChatMessageContentDeserializer.java`

- [ ] **Step 1: 写多模态协议失败测试**

在 `ChatMessageTest` 增加以下精确场景：

```java
ChatMessage message = ChatMessage.userMultimodal(List.of(
        new ChatMessage.TextPart("检查页面"),
        new ChatMessage.ImageUrlPart(new ChatMessage.ImageUrl(
                "data:image/png;base64,AQID",
                ChatMessage.ImageDetail.HIGH))));

JsonNode json = objectMapper.valueToTree(message);
assertThat(json.at("/content/0/type").textValue()).isEqualTo("text");
assertThat(json.at("/content/0/text").textValue()).isEqualTo("检查页面");
assertThat(json.at("/content/1/type").textValue()).isEqualTo("image_url");
assertThat(json.at("/content/1/image_url/url").textValue())
        .isEqualTo("data:image/png;base64,AQID");
assertThat(json.at("/content/1/image_url/detail").textValue()).isEqualTo("high");
```

再把该 JSON 反序列化为 `ChatMessage`，断言 content 是包含相同两个 part 的
`MultimodalContent`。保留现有纯文本和工具调用断言，并增加以下拒绝测试：空 parts、
空 URL、未知 detail、未知 part type、字段大小写变化和缺失 `image_url`
对象均抛出明确异常。

- [ ] **Step 2: 运行测试并确认红灯**

Run: `mvn -pl agent-core -am -Dtest=ChatMessageTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: `agent-core` 测试编译失败，原因是 `userMultimodal`、内容类型和序列化器尚不存在。

- [ ] **Step 3: 定义内容 sealed 层次**

把 `ChatMessage.content` 的类型改为带自定义 Jackson serializer/deserializer 的
`Content`。在 `ChatMessage` 中增加以下 public 内嵌类型：

```java
@JsonSerialize(using = ChatMessageContentSerializer.class)
@JsonDeserialize(using = ChatMessageContentDeserializer.class)
public sealed interface Content permits TextContent, MultimodalContent {
}

public record TextContent(String text) implements Content {
    public TextContent {
        Objects.requireNonNull(text, "text 不能为空");
    }
}

public record MultimodalContent(List<ContentPart> parts) implements Content {
    public MultimodalContent {
        Objects.requireNonNull(parts, "parts 不能为空");
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("parts 不能为空列表");
        }
        parts = List.copyOf(parts);
    }
}

public sealed interface ContentPart permits TextPart, ImageUrlPart {
}

public record TextPart(String text) implements ContentPart {
    public TextPart {
        Objects.requireNonNull(text, "text 不能为空");
    }
}

public record ImageUrlPart(ImageUrl imageUrl) implements ContentPart {
    public ImageUrlPart {
        Objects.requireNonNull(imageUrl, "imageUrl 不能为空");
    }
}

public record ImageUrl(String url, ImageDetail detail) {
    public ImageUrl {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url 不能为空");
        }
        Objects.requireNonNull(detail, "detail 不能为空");
    }
}
```

`ImageDetail` 使用精确 JSON 值：

```java
public enum ImageDetail {
    AUTO("auto"), LOW("low"), HIGH("high");

    private final String jsonValue;

    ImageDetail(String jsonValue) { this.jsonValue = jsonValue; }

    @JsonValue
    public String jsonValue() { return jsonValue; }

    @JsonCreator
    public static ImageDetail fromJson(String value) {
        return Arrays.stream(values())
                .filter(detail -> detail.jsonValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知图像细节: " + value));
    }
}
```

纯文本工厂改为创建 `TextContent`，工具调用消息 content 保持 null。新增：

```java
public static ChatMessage userMultimodal(List<ContentPart> parts) {
    return new ChatMessage(
            Role.USER,
            new MultimodalContent(parts),
            null,
            null,
            List.of());
}
```

- [ ] **Step 4: 实现精确 JSON 形状**

`ChatMessageContentSerializer` 对 `TextContent` 写 JSON string；对
`MultimodalContent` 写数组，并把 part 精确写成以下形状：

```json
{"type":"text","text":"检查页面"}
{"type":"image_url","image_url":{"url":"data:image/png;base64,AQID","detail":"high"}}
```

`ChatMessageContentDeserializer` 只接受 textual node、array node 或 null。
数组元素必须是 object，使用 `node.get("type").textValue()` 与精确字符串
`text`、`image_url` 比较；要求规定字段存在且类型正确。任何未知或不完整结构都
抛出 `JsonMappingException`，不修正字段名称。所有新增类与复杂方法添加中文
Javadoc。

- [ ] **Step 5: 运行 LLM 协议测试并确认绿灯**

Run: `mvn -pl agent-core -am -Dtest=ChatMessageTest,LlmClientTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: 纯文本、工具调用、SSE 和多模态协议测试全部通过。

- [ ] **Step 6: 提交多模态协议**

```text
feat(core): 支持强类型多模态聊天消息
```

### Task 3: 浏览器异步协议与结果模型

**Files:**
- Create: `agent-sandbox/src/test/java/com/agent/sandbox/browser/BrowserModelTest.java`
- Create: `agent-sandbox/src/main/java/com/agent/sandbox/browser/BrowserAutomation.java`
- Create: `agent-sandbox/src/main/java/com/agent/sandbox/browser/NavigationResult.java`
- Create: `agent-sandbox/src/main/java/com/agent/sandbox/browser/BrowserScreenshot.java`
- Create: `agent-sandbox/src/main/java/com/agent/sandbox/browser/BrowserAutomationException.java`

- [ ] **Step 1: 写浏览器模型失败测试**

测试 `NavigationResult` 保存请求 URI、最终 URI 和 `OptionalInt` 状态码并拒绝 null。
测试 `BrowserScreenshot` 仅接受 `image/png`、拒绝空字节，并通过以下方式证明构造
和访问都防御性复制：

```java
byte[] source = {1, 2, 3};
BrowserScreenshot screenshot = new BrowserScreenshot(source, "image/png");
source[0] = 9;
byte[] returned = screenshot.pngBytes();
returned[1] = 9;

assertThat(screenshot.pngBytes()).containsExactly(1, 2, 3);
```

- [ ] **Step 2: 运行测试并确认红灯**

Run: `mvn -pl agent-sandbox -Dtest=BrowserModelTest test`

Expected: 测试编译失败，原因是浏览器协议类型尚不存在。

- [ ] **Step 3: 实现协议和异常**

```java
public interface BrowserAutomation extends AutoCloseable {
    CompletableFuture<NavigationResult> navigate(URI url, Duration timeout);
    CompletableFuture<Void> click(String selector, Duration timeout);
    CompletableFuture<String> extractDom();
    CompletableFuture<BrowserScreenshot> screenshot(Duration timeout);
}
```

`NavigationResult` 紧凑构造器对三个字段执行 `Objects.requireNonNull`。
`BrowserScreenshot` 紧凑构造器要求非空字节和精确媒体类型，并覆写访问器：

```java
public BrowserScreenshot {
    Objects.requireNonNull(pngBytes, "pngBytes 不能为空");
    if (pngBytes.length == 0) {
        throw new IllegalArgumentException("pngBytes 不能为空字节");
    }
    if (!"image/png".equals(mediaType)) {
        throw new IllegalArgumentException("mediaType 必须是 image/png");
    }
    pngBytes = pngBytes.clone();
}

@Override
public byte[] pngBytes() {
    return pngBytes.clone();
}
```

`BrowserAutomationException` 提供 message 构造器和 message/cause 构造器。所有
公开类型、构造器和方法添加中文 Javadoc。

- [ ] **Step 4: 运行浏览器模型测试并确认绿灯**

Run: `mvn -pl agent-sandbox -Dtest=BrowserModelTest test`

Expected: 模型校验、防御性复制和协议签名测试全部通过。

- [ ] **Step 5: 提交浏览器协议**

```text
feat(sandbox): 定义异步浏览器自动化协议
```

### Task 4: 真实 Playwright 浏览器服务

**Files:**
- Create: `agent-sandbox/src/test/java/com/agent/sandbox/browser/PlaywrightBrowserServiceTest.java`
- Create: `agent-sandbox/src/main/java/com/agent/sandbox/browser/PlaywrightBrowserService.java`

- [ ] **Step 1: 写真实 Chromium 失败测试**

测试类使用 JDK `HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)`
提供 UTF-8 HTML：

```html
<!doctype html>
<html>
  <body>
    <button id="change" onclick="document.querySelector('#value').textContent='after'">change</button>
    <div id="value">before</div>
  </body>
</html>
```

仅当创建 `Playwright` 并启动 headless Chromium 明确失败时，使用
`Assumptions.assumeTrue(false, exception.getMessage())` 跳过。主测试执行：

```java
try (PlaywrightBrowserService service = new PlaywrightBrowserService()) {
    NavigationResult navigation = service.navigate(url, Duration.ofSeconds(15)).get();
    assertThat(navigation.requestedUrl()).isEqualTo(url);
    assertThat(navigation.finalUrl()).isEqualTo(url);
    assertThat(navigation.statusCode()).hasValue(200);

    assertThat(service.extractDom().get()).contains("before");
    service.click("#change", Duration.ofSeconds(15)).get();
    assertThat(service.extractDom().get()).contains("after");

    BrowserScreenshot screenshot = service.screenshot(Duration.ofSeconds(15)).get();
    assertThat(screenshot.pngBytes())
            .startsWith(
                    (byte) 0x89,
                    (byte) 0x50,
                    (byte) 0x4E,
                    (byte) 0x47,
                    (byte) 0x0D,
                    (byte) 0x0A,
                    (byte) 0x1A,
                    (byte) 0x0A);
}
```

另测相对 URI、`file` URI、非正 timeout 和空 selector 被同步拒绝；不存在 selector
在短 timeout 后以 `BrowserAutomationException` 完成失败；close 后四种操作都拒绝，
重复 close 无异常。`@AfterEach` 必须停止本地 HTTP 服务。

- [ ] **Step 2: 运行真实测试并确认红灯**

Run: `mvn -pl agent-sandbox -Dtest=PlaywrightBrowserServiceTest test`

Expected: 测试编译失败，原因是 `PlaywrightBrowserService` 尚不存在；当前环境不得显示 skipped。

- [ ] **Step 3: 实现虚拟线程与延迟初始化**

服务字段精确包含单线程执行器、关闭标志及四个 Playwright 资源：

```java
private final ExecutorService executor = Executors.newSingleThreadExecutor(
        Thread.ofVirtual().name("playwright-browser-", 0).factory());
private final AtomicBoolean closed = new AtomicBoolean();
private Playwright playwright;
private Browser browser;
private BrowserContext browserContext;
private Page page;
```

无参构造器不创建 Playwright。每个异步方法用 `CompletableFuture.supplyAsync` 或
`runAsync` 提交到 executor；工作任务先调用只在该线程执行的 `ensureInitialized()`：

```java
playwright = Playwright.create();
browser = playwright.chromium().launch(
        new BrowserType.LaunchOptions().setHeadless(true));
browserContext = browser.newContext();
page = browserContext.newPage();
```

初始化与操作中的 RuntimeException 保留为 `BrowserAutomationException` cause。
`RejectedExecutionException` 转换为关闭错误。提交前用 `AtomicBoolean` 拒绝已关闭服务。

- [ ] **Step 4: 实现导航、点击、DOM 与截图**

URL 校验要求 `url.isAbsolute()` 且 scheme 精确等于 `http` 或 `https`。Duration
转换为 `timeout.toMillis()` 前要求正数。实现使用以下 Playwright API：

```java
Response response = page.navigate(
        url.toString(),
        new Page.NavigateOptions().setTimeout(timeout.toMillis()));

page.locator(selector).click(
        new Locator.ClickOptions().setTimeout(timeout.toMillis()));

String dom = page.content();

byte[] png = page.screenshot(new Page.ScreenshotOptions()
        .setFullPage(true)
        .setType(ScreenshotType.PNG)
        .setTimeout(timeout.toMillis()));
```

导航结果的最终 URI 精确取 `URI.create(page.url())`，状态码在 response 为 null 时
使用 `OptionalInt.empty()`。

- [ ] **Step 5: 实现同线程清理**

`close()` 第一次调用先把 closed 改为 true，再向同一 executor 提交清理任务并
等待完成。按 Page、BrowserContext、Browser、Playwright 顺序关闭非 null 资源，
第一个失败作为主 `BrowserAutomationException`，其余失败作为 suppressed exception；
最后调用 `executor.close()`。等待被中断时恢复当前线程中断标志并保留 cause。

- [ ] **Step 6: 运行真实浏览器测试并确认绿灯**

Run: `mvn -pl agent-sandbox -Dtest=BrowserModelTest,PlaywrightBrowserServiceTest test`

Expected: 当前环境真实 Chromium 启动，导航、点击、DOM、PNG、超时与关闭测试全部通过，skipped 为 0。

- [ ] **Step 7: 提交 Playwright 服务**

```text
feat(sandbox): 实现 Playwright 浏览器服务
```

### Task 5: ModelRouter 与 Resilience4j 降级

**Files:**
- Create: `agent-core/src/test/java/com/agent/core/llm/ModelRouterTest.java`
- Create: `agent-core/src/main/java/com/agent/core/llm/TaskType.java`
- Create: `agent-core/src/main/java/com/agent/core/llm/ModelEndpoint.java`
- Create: `agent-core/src/main/java/com/agent/core/llm/ModelRequest.java`
- Create: `agent-core/src/main/java/com/agent/core/llm/RoutedCompletion.java`
- Create: `agent-core/src/main/java/com/agent/core/llm/ModelEndpointException.java`
- Create: `agent-core/src/main/java/com/agent/core/llm/ModelRoutingException.java`
- Create: `agent-core/src/main/java/com/agent/core/llm/ModelRouter.java`

- [ ] **Step 1: 写路由和降级失败测试**

使用三个 `RestClient.Builder`、三个 `MockRestServiceServer` 和真实 `LlmClient`。
构造路由时使用精确任务和端点：

```java
Map<TaskType, List<ModelEndpoint>> routes = Map.of(
        TaskType.CODE, List.of(codeEndpoint),
        TaskType.VISION, List.of(primaryVision, fallbackVision),
        TaskType.QUICK_CLASSIFICATION, List.of(classificationEndpoint));
```

覆盖以下场景：

1. 三个 TaskType 分别请求其精确模型。
2. VISION 主端点返回 HTTP 502，fallback 返回成功；断言端点名和模型名来自 fallback。
3. 主熔断器显式 `transitionToOpenState()` 后不发 HTTP 请求，直接使用 fallback。
4. 主端点返回空 choices 后进入 fallback，并由主熔断器记录失败。
5. 全部熔断器打开时抛 `ModelRoutingException`，suppressed exception 顺序与端点顺序一致，根因包含 `CallNotPermittedException`。
6. 缺少任一 TaskType、空端点列表、空 endpoint name、空 model 和 null 依赖均被拒绝。
7. 修改构造器输入 Map 或 List 不改变 Router 内部路由。

每个测试在 `finally` 或 `@AfterEach` 中关闭创建的全部 `LlmClient`，并对所有
`MockRestServiceServer` 调用 `verify()`，防止虚拟线程执行器或未验证请求遗留。

- [ ] **Step 2: 运行测试并确认红灯**

Run: `mvn -pl agent-core -am -Dtest=ModelRouterTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: 测试编译失败，原因是路由公开类型尚不存在。

- [ ] **Step 3: 实现不可变路由模型**

`TaskType` 精确包含：

```java
public enum TaskType {
    CODE,
    VISION,
    QUICK_CLASSIFICATION
}
```

实现以下三个 record：

```java
public record ModelEndpoint(
        String name,
        String model,
        LlmClient client,
        CircuitBreaker circuitBreaker) {
    public ModelEndpoint {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model 不能为空");
        }
        Objects.requireNonNull(client, "client 不能为空");
        Objects.requireNonNull(circuitBreaker, "circuitBreaker 不能为空");
    }
}

public record ModelRequest(
        List<ChatMessage> messages,
        List<LlmClient.Tool> tools,
        JsonNode toolChoice,
        Double temperature) {
    public ModelRequest {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages 不能为空"));
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}

public record RoutedCompletion(
        String endpointName,
        String model,
        LlmClient.ChatCompletionResponse response) {
    public RoutedCompletion {
        if (endpointName == null || endpointName.isBlank()) {
            throw new IllegalArgumentException("endpointName 不能为空");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model 不能为空");
        }
        Objects.requireNonNull(response, "response 不能为空");
    }
}
```

两个异常类都提供保留 cause 的构造器。`ModelEndpointException` 构造器接收精确
endpoint name、model 与 cause，并把二者写入异常消息；`ModelRoutingException`
构造器接收 `TaskType` 并生成含精确任务名称的消息。

- [ ] **Step 4: 实现构造器注入与顺序降级**

`ModelRouter` 构造器逐个遍历 `TaskType.values()`，要求每个精确键存在非空列表，
并复制为不可修改 `EnumMap`。执行核心保持以下顺序：

```java
for (ModelEndpoint endpoint : routes.get(taskType)) {
    try {
        LlmClient.ChatCompletionResponse response = endpoint.circuitBreaker()
                .executeSupplier(() -> validatedComplete(endpoint, request));
        return new RoutedCompletion(endpoint.name(), endpoint.model(), response);
    } catch (RuntimeException exception) {
        failures.add(new ModelEndpointException(
                endpoint.name(), endpoint.model(), exception));
    }
}

ModelRoutingException routingException = new ModelRoutingException(taskType);
failures.forEach(routingException::addSuppressed);
throw routingException;
```

`validatedComplete` 使用 endpoint model 新建 `LlmClient.ChatCompletionRequest`，stream
固定为 false；调用 client 后要求 response、choices 第一项和第一项 message 非 null。
校验位于 `executeSupplier` 内，使协议失败进入该 endpoint 的熔断统计。只捕获
RuntimeException，不捕获 Error。Router 不关闭或重置注入资源。

- [ ] **Step 5: 运行路由与 LLM 测试并确认绿灯**

Run: `mvn -pl agent-core -am -Dtest=ModelRouterTest,LlmClientTest,ChatMessageTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: 精确路由、顺序降级、熔断拒绝、响应校验、异常聚合及已有 LLM 测试全部通过。

- [ ] **Step 6: 提交模型路由**

```text
feat(core): 实现模型路由与熔断降级
```

### Task 6: ReviewerNode 多模态审查

**Files:**
- Create: `agent-core/src/test/java/com/agent/core/nodes/ReviewerNodeTest.java`
- Create: `agent-core/src/test/java/com/agent/core/nodes/CoderOpsReviewerGraphTest.java`
- Create: `agent-core/src/main/java/com/agent/core/nodes/ReviewerNode.java`

- [ ] **Step 1: 写 ReviewerNode 失败测试**

创建可控 `BrowserAutomation` 测试实现：导航返回最终 URL 与 200，DOM 返回
`<html><body>ready</body></html>`，截图返回字节 `{1, 2, 3}`。使用真实
`ModelRouter`、真实 `LlmClient` 与 `MockRestServiceServer`，HTTP matcher 必须验证
请求 JSON 同时包含：

```text
data:image/png;base64,AQID
<html><body>ready</body></html>
ops.exitCode
ops.stdout
ops.stderr
ops.timedOut
```

模型成功响应 content 使用转义后的完整 JSON：

```json
{"approved":true,"summary":"测试和页面正常","feedback":"无需修改"}
```

断言精确状态：

```java
assertThat(result.variables())
        .containsEntry("reviewer.approved", "true")
        .containsEntry("reviewer.summary", "测试和页面正常")
        .containsEntry("reviewer.feedback", "无需修改")
        .containsEntry("reviewer.model", "vision-model");
assertThat(result.trace()).containsExactly("reviewer");
```

再覆盖 `approved=false`、只提供 `ops.error`、缺少 Ops 证据、相对 URL、Markdown
代码围栏、多余 JSON 字段、非文本助手 content、浏览器 future 失败和 Router 全部
失败。失败场景断言 `reviewer.error` 包含异常类、消息、cause 或 suppressed 信息，
并且 trace 仍追加 `reviewer`。

同一步创建 `CoderOpsReviewerGraphTest`。用 `@TempDir` 初始化真实 Git 工作树和
`value.txt`，真实 `AstService` 应用把 `before` 改为 `after` 的 Unified Diff。
终端测试实现读取修改后的文件并返回 `CommandResult(0, "after", "", false)`；
浏览器测试实现返回固定 DOM 与 PNG；模型使用真实 `LlmClient`、真实
`ModelRouter` 和模拟 HTTP 成功响应。图精确注册：

```java
graph.addNode("coder", coderNode)
        .addNode("ops", opsNode)
        .addNode("reviewer", reviewerNode)
        .addEdge("coder", "ops")
        .addEdge("ops", "reviewer")
        .addEdge("reviewer", StateGraph.END)
        .setEntryPoint("coder");
```

初始状态写入三个节点的公开输入键。断言文件内容和 Ops stdout 都为 `after`、
审查 approved 为 true，并断言 trace 精确等于 `coder`、`ops`、`reviewer`。

- [ ] **Step 2: 运行测试并确认红灯**

Run: `mvn -pl agent-core -am -Dtest=ReviewerNodeTest,CoderOpsReviewerGraphTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: 两个测试都编译失败，原因是 `ReviewerNode` 尚不存在；闭环测试在生产
实现前形成真实红灯。

- [ ] **Step 3: 实现构造器与精确状态键**

```java
public static final String URL_KEY = "reviewer.url";
public static final String APPROVED_KEY = "reviewer.approved";
public static final String SUMMARY_KEY = "reviewer.summary";
public static final String FEEDBACK_KEY = "reviewer.feedback";
public static final String MODEL_KEY = "reviewer.model";
public static final String ERROR_KEY = "reviewer.error";
```

构造器注入 `BrowserAutomation`、`ModelRouter`、`ObjectMapper` 与 `Duration`，拒绝
null 和非正 browserTimeout。节点内部定义严格 `ReviewerDecision(boolean approved,
String summary, String feedback)`，紧凑构造器拒绝 null 字符串。

- [ ] **Step 4: 实现浏览器与 Ops 证据收集**

`execute` 先要求非空 `reviewer.url` 并调用 `URI.create`。Ops 证据校验只接受：

```java
boolean hasCompleteResult = variables.containsKey(OpsNode.EXIT_CODE_KEY)
        && variables.containsKey(OpsNode.STDOUT_KEY)
        && variables.containsKey(OpsNode.STDERR_KEY)
        && variables.containsKey(OpsNode.TIMED_OUT_KEY);
boolean hasError = variables.get(OpsNode.ERROR_KEY) != null
        && !variables.get(OpsNode.ERROR_KEY).isBlank();
```

两者都为 false 时抛输入异常。依次等待 navigate、extractDom 和 screenshot future；
捕获 `InterruptedException` 时恢复中断标志。文本证据使用固定标签和实际存在的
精确状态键，不对日志内容作转换。

- [ ] **Step 5: 实现多模态请求与严格响应解析**

截图 Data URL 精确构造：

```java
String imageUrl = "data:image/png;base64,"
        + Base64.getEncoder().encodeToString(screenshot.pngBytes());
```

ModelRequest messages 包含中文 system 指令和由 `TextPart`、`ImageUrlPart` 组成的
userMultimodal 消息，TaskType 固定为 `VISION`，tools 为空，toolChoice 与
temperature 为 null。system 指令要求只返回 `approved`、`summary`、`feedback`
三个精确 JSON 字段。

读取第一 choice message 时只接受 `ChatMessage.TextContent`。使用以下 reader，
确保调用方 ObjectMapper 即使关闭全局未知字段检查，节点仍严格拒绝多余字段：

```java
ObjectReader decisionReader = objectMapper.readerFor(ReviewerDecision.class)
        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
ReviewerDecision decision = decisionReader.readValue(textContent.text());
```

成功时写入四个结果键并追加 trace。任何 Exception 都用 `PrintWriter` 和
`StringWriter` 保存完整堆栈到 `reviewer.error` 并追加相同 trace；不关闭注入资源。

- [ ] **Step 6: 运行节点测试并确认绿灯**

Run: `mvn -pl agent-core -am -Dtest=ReviewerNodeTest,CoderOpsReviewerGraphTest,CoderOpsGraphTest,OpsNodeTest,CoderNodeTest,StateGraphTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: 批准、拒绝、Ops 错误、真实请求内容、严格 JSON、完整堆栈和三节点闭环
全部通过；闭环 trace 顺序精确，已有节点与图引擎无回归。

- [ ] **Step 7: 提交 ReviewerNode**

```text
feat(core): 实现多模态审查闭环
```

### Task 7: Phase 3 完整验收

**Files:**
- Verify: `.gitignore`
- Verify: `docs/superpowers/specs/2026-08-01-phase-3-browser-router-design.md`
- Verify: all Phase 3 production and test files

- [ ] **Step 1: 记录测试前进程基线**

Run:

```powershell
$phase3ProcessBaseline = Get-Process |
    Where-Object { $_.ProcessName -match 'chrome|chromium|playwright|node' } |
    Select-Object -ExpandProperty Id
```

Expected: 命令成功保存测试前已有进程 ID，不终止用户已有进程。

- [ ] **Step 2: 运行完整 Maven 验证**

Run: `mvn clean verify`

Expected: 五个 reactor 模块全部 `SUCCESS`，无测试失败或错误；当前环境
`PlaywrightBrowserServiceTest` skipped 精确为 0。

- [ ] **Step 3: 汇总 Surefire 报告**

Run:

```powershell
$phase3Reports = Get-ChildItem -Recurse -Filter 'TEST-*.xml' |
    Where-Object { $_.FullName -match '\\target\\surefire-reports\\' }
$phase3Totals = [ordered]@{ Tests = 0; Failures = 0; Errors = 0; Skipped = 0 }
foreach ($phase3Report in $phase3Reports) {
    [xml]$phase3Xml = Get-Content -Raw $phase3Report.FullName
    $phase3Totals.Tests += [int]$phase3Xml.testsuite.tests
    $phase3Totals.Failures += [int]$phase3Xml.testsuite.failures
    $phase3Totals.Errors += [int]$phase3Xml.testsuite.errors
    $phase3Totals.Skipped += [int]$phase3Xml.testsuite.skipped
}
$phase3Totals
```

Expected: Failures 与 Errors 都为 0。打开
`agent-sandbox/target/surefire-reports/TEST-com.agent.sandbox.browser.PlaywrightBrowserServiceTest.xml`
确认该 suite tests 大于 0 且 skipped 为 0。

- [ ] **Step 4: 检查禁止依赖和 Java 21**

Run: `mvn -pl agent-core,agent-sandbox -am dependency:tree`

Expected: 包含设计指定依赖，不含 `langchain4j` 或 `langgraph4j`。

Run: `java -version`

Expected: 输出 Java `21`。

- [ ] **Step 5: 检查测试进程清理**

Run:

```powershell
$phase3NewProcesses = Get-Process |
    Where-Object { $_.ProcessName -match 'chrome|chromium|playwright|node' } |
    Where-Object { $_.Id -notin $phase3ProcessBaseline }
$phase3NewProcesses | Select-Object Id,ProcessName,Path
```

Expected: 没有 Phase 3 测试新建后仍存活的 Playwright、Chromium 或 Node 进程。

- [ ] **Step 6: 检查 Git 与排除规则**

Run: `git status --short`

Expected: 只显示实施计划中尚未提交的 Phase 3 文件；不得出现 `target/`、浏览器
二进制、截图、IDE 文件、日志或密钥。

Run: `git check-ignore -v agent-core/target agent-sandbox/target tmp`

Expected: 三个路径均由根 `.gitignore` 的现有规则排除。只有实际出现新的仓库内
产物路径时才更新 `.gitignore`，且更新必须与产生该路径的任务同一提交。

- [ ] **Step 7: 复核提交历史与最终差异**

Run: `git log --oneline --decorate -10`

Expected: Phase 3 依赖、多模态、浏览器协议、Playwright 服务、模型路由、审查节点
和图闭环均使用 Conventional Commits，scope 必填，单次提交不混入无关文件。

Run: `git diff HEAD~7..HEAD --check`

Expected: 无空白错误。若 Phase 3 实际提交数量不同，先从 `git log` 读取精确起点
提交，再以该提交到 HEAD 的精确范围执行 `git diff --check`，不按提交信息猜测。
