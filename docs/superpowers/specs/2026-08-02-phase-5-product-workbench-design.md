# Phase 5 Product Workbench & Web API Design

## 目标

Phase 5 在现有 Run、Checkpoint、HITL 与 Trace 生命周期之上建立可直接操作的 Web
工作台：

- 保留已有任务提交、状态查询和批准/拒绝 API，并增加历史查询与受控参数修改。
- 将 `OpsNode` 的 ANSI 终端片段按 Run 实时推送到 WebSocket 与 SSE。
- 使用 React、Monaco Editor 和 xterm.js 呈现代码 Diff、终端、审批和视觉审查证据。
- 将前端构建纳入 `agent-web` Maven 生命周期，最终产物随 Spring Boot JAR 发布。
- 使用 Java、前端单元测试和真实浏览器测试验证完整产品闭环。

本阶段不引入第二套 Run 状态、外部前端服务、第三方 Agent 框架或日志数据库。

## 已批准方案

采用 `agent-web` 内嵌 React + TypeScript + Vite 的单体发布方式。后端继续以 PostgreSQL
Checkpoint 为权威状态，进程内总线只传输实时终端片段。工作台通过同源 REST、SSE 和
WebSocket 消费现有生命周期。

未采用的路线：

- WebFlux 静态 HTML 与 WebJars：构建步骤少，但 Monaco、xterm.js、复杂状态同步和组件
  测试的维护成本更高。
- 独立前端服务：部署边界清晰，但会增加跨域、独立发布和运维范围，不符合本阶段单一
  Workbench 目标。

## 模块边界

### agent-core

`agent-core` 增加节点执行上下文与实时终端日志领域协议：

- `NodeExecutionContext` 只包含精确 `runId` 与 `nodeName`。
- `Node` 保持函数式接口；新增默认上下文重载，现有 Lambda 与节点不受影响。
- `StateGraph` 调用上下文重载，使节点在不写入临时状态键的前提下获知当前 Run。
- `RunLogStream`、`RunLogEvent` 与 `RunLogPublisher` 定义强类型日志端口。
- `OpsNode` 在上下文执行时发布实时日志，直接调用旧 `execute(AgentState)` 时保持原行为。
- `ApprovalCommand` 接收不可变 `variableUpdates`，由 `AgentRunService` 执行严格白名单校验。
- `ReviewerNode` 将浏览器证据写入不可变 `AgentState`，供 Checkpoint 和工作台读取。

Core 不依赖 Reactor、WebSocket、SSE、React 或浏览器 UI。

### agent-web

`agent-web` 实现：

- 多订阅者、有界、按 `runId` 隔离的 `InMemoryRunLogEventBus`。
- 终端 WebSocket 与 SSE 适配器。
- Trace 发布组合器，在终态 Trace 后完成对应日志流。
- Run 历史 REST API 与扩展审批请求。
- React/Vite 静态资源构建和 Spring Boot 同包发布。

PostgreSQL 继续只保存 Checkpoint。实时日志未进入 Checkpoint 前不持久化；节点完成后的
完整 `ops.stdout`、`ops.stderr` 仍随 `AgentState` 落库。

## Core 公开协议

### 节点执行上下文

新增 `com.agent.core.engine.NodeExecutionContext`：

```java
public record NodeExecutionContext(UUID runId, String nodeName) {
}
```

字段非空，`nodeName` 非空白。`Node` 增加默认方法：

```java
default AgentState execute(NodeExecutionContext context, AgentState state)
        throws Exception {
    Objects.requireNonNull(context, "context 不能为空");
    return execute(state);
}
```

`Node` 仍只有一个抽象方法。`StateGraph` 使用 `GraphExecutionRequest.runId()` 和当前精确
节点名创建上下文，并在现有虚拟线程中调用该重载。

### 实时终端日志

以下类型位于 `com.agent.core.trace`：

```java
public enum RunLogStream {
    STDOUT,
    STDERR,
    PTY
}

public record RunLogEvent(
        UUID eventId,
        UUID runId,
        String nodeName,
        long sequence,
        RunLogStream stream,
        String text,
        Instant occurredAt) {
}

@FunctionalInterface
public interface RunLogPublisher {
    void publish(RunLogEvent event);
}
```

`sequence` 从 0 开始且不得为负数；文本允许为空但不得为 null，以保持终端原始分片和
ANSI 转义序列。`RunLogPublisher.noop()` 提供无副作用默认值。

`OpsNode` 保留现有三参数构造器，并新增四参数构造器接收 `RunLogPublisher`。上下文执行
为每次命令创建独立 `AtomicLong`，将沙箱 `STDOUT`、`STDERR`、`PTY` 通过显式 switch
映射到 `RunLogStream`。发布异常不终止命令；完整堆栈写入新增状态键
`ops.logError`，最终命令结果仍写入既有四个成功结果键。

### 可修改审批

`ApprovalCommand` 扩展为：

```java
public record ApprovalCommand(
        ApprovalDecision decision,
        long expectedVersion,
        String reason,
        Map<String, String> variableUpdates) {

    public ApprovalCommand(
            ApprovalDecision decision,
            long expectedVersion,
            String reason) {
        this(decision, expectedVersion, reason, Map.of());
    }
}
```

更新 Map 防御性复制，键不得为空白，值不得为 null。规则固定为：

- `REJECT` 必须使用空更新 Map。
- `APPROVE` 可使用空更新 Map。
- 非空更新中的每个键必须同时存在于等待快照的
  `interruptRequest.details()` 和 `state.variables()`。
- 名称只执行精确匹配，不改写前缀、大小写或格式。
- 校验通过后按 Map 更新不可变 `AgentState`，再追加批准后的 `RUNNING` Checkpoint。
- 任一键不合法时抛 `IllegalArgumentException`，REST 映射为 HTTP 400，不产生新版本。

图作者若允许修改 `ops.command`，必须将精确键 `ops.command` 同时放入状态变量与
`InterruptRequest.details`。工作台不推断 `command` 与 `ops.command` 的关系。

### Reviewer 证据

`ReviewerNode` 新增精确状态键：

```text
reviewer.finalUrl
reviewer.dom
reviewer.screenshotDataUrl
```

`reviewer.screenshotDataUrl` 固定为 `data:image/png;base64,...`。浏览器证据一旦成功获取，
即先写入新 `AgentState`；后续模型路由或响应解析失败时仍保留证据，并追加既有
`reviewer.error` 完整堆栈。

## Web 日志总线

`InMemoryRunLogEventBus` 位于 `com.agent.web.log`，实现 `RunLogPublisher` 与
`AutoCloseable`。

- `subscribe(UUID runId)` 返回不重放历史事件的 `Flux<RunLogEvent>`。
- `openSubscription(UUID runId)` 立即占用订阅并返回公开的可关闭
  `RunLogSubscription`；其 `events()` 在终止时自动执行幂等 `close()`。
- 同一 Run 允许 SSE 与 WebSocket 等多个并发订阅者。
- 每个订阅者拥有独立 1024 条 `ArrayBlockingQueue`，慢订阅者溢出时只完成该订阅并记录
  完整服务日志，不影响其他订阅者与 Run。
- 没有订阅者时实时片段直接丢弃，最终完整输出仍由 Checkpoint 提供。
- `complete(UUID runId)` 完成并移除该 Run 的全部订阅。
- `close()` 完成全部订阅并拒绝后续发布或订阅。

`RunLifecycleEventPublisher` 位于 `com.agent.web.trace`，实现 `TraceEventPublisher`。它先将
每个 Trace 交给现有 `InMemoryTraceEventBus`；遇到 `COMPLETED`、`FAILED` 或 `REJECTED`
时在 `finally` 语义下调用日志总线 `complete(runId)`。Trace 发布异常继续交给
`AgentRunService` 记录，不阻止日志连接清理。

## HTTP 与 WebSocket 协议

### 既有 Run API

以下路径保持：

```text
POST /api/runs
GET  /api/runs/{runId}
POST /api/runs/{runId}/approval
GET  /ws/runs/{runId}/trace
```

审批请求增加可省略的 `variableUpdates`。省略时精确等价于空 Map，保证 Phase 4 客户端
兼容；未知字段仍返回 400。

新增历史查询：

```text
GET /api/runs/{runId}/history
```

返回按版本升序的不可变 `List<RunView>`。Run 不存在返回 404。

### 终端快照

`TerminalSnapshot` 字段固定为：

```text
runId
checkpointVersion
stdout
stderr
exitCode
timedOut
error
```

`stdout`、`stderr` 从最新状态的精确键读取，缺失时为空字符串；其余结果字段缺失时为
null。数值和布尔字符串必须严格解析，非法持久化值导致完整错误返回，不静默修正。

### WebSocket

新增路径：

```text
/ws/runs/{runId}/terminal
```

连接通过 `openSubscription(runId)` 先占用有界日志订阅，再读取权威 Checkpoint，避免快照
读取期间丢失事件。首帧为：

```json
{
  "kind": "SNAPSHOT",
  "terminal": {}
}
```

后续帧为：

```json
{
  "kind": "LOG",
  "event": {}
}
```

Run 不存在使用关闭码 4404。终态、客户端断开、序列化失败和快照读取异常均释放订阅。

### SSE

新增：

```text
GET /api/runs/{runId}/logs
Accept: text/event-stream
```

响应同样先通过 `openSubscription(runId)` 占用订阅，再读取 Checkpoint；发送顺序为事件名
`snapshot`，随后为事件名 `log`。两种事件的 data 使用与 WebSocket 完全相同的
`SNAPSHOT`、`LOG` frame JSON；`id` 分别使用 Checkpoint version 和日志 eventId。终态后
正常完成流，Run 不存在返回 404，所有终止路径关闭订阅。

## Web Workbench

前端源码位于：

```text
agent-web/src/main/frontend
```

采用单页、同源部署，不增加前端路由依赖。主要区域：

- 顶部 Run bar：产品名、连接状态、Run ID、生命周期状态和启动/重新加载命令。
- 左侧 Run launcher：精确 `graphId` 和 `initialState` JSON；创建成功后切换当前 Run。
- 主工作区三个 Tab：Code、Terminal、Review。
- 右侧 Timeline：由 Trace WebSocket 的七种事件按顺序呈现。
- HITL Dialog：当状态为 `WAITING_APPROVAL` 时出现，展示 reason 与 details。

整体采用浅色、中性、高密度操作台视觉；用边线和分区代替嵌套卡片。终端保持深色以保证
ANSI 可读性，Diff 使用标准红/绿语义，审批使用琥珀警示。桌面为可调整的三栏布局，窄屏
变为顶部 Run bar、单列 Tab 和底部审批 Dialog。所有固定工具栏、Tab 和终端区域使用稳定
尺寸，动态状态不得造成布局跳动。

### Monaco Diff

前端以 `parse-diff` 解析状态中的精确 `coder.unifiedDiff`。每个文件的 hunk 被转换为
`original` 与 `modified` 文本，文件选择器使用解析结果的精确路径。Monaco
`DiffEditor` 使用只读、并排模式；窄屏改为 inline diff。解析失败显示错误状态并保留原始
Unified Diff，不自行修正路径或格式。

### xterm.js

Terminal Tab 连接终端 WebSocket。收到快照后清空终端并依次写入完整 stdout/stderr；实时
`LOG` frame 的 `text` 原样传给 xterm，不移除 ANSI。`FitAddon` 结合 `ResizeObserver`
适配容器。切换 Run 或卸载组件时关闭旧 WebSocket 和 observer。

### HITL

Dialog 固定提供“批准”“修改”“拒绝”：

- 批准发送 `APPROVE` 与空 `variableUpdates`。
- 修改只展示同时存在于 details 与 state.variables 的精确键；提交时发送 `APPROVE` 与
  编辑后的 Map，按钮文案为“批准修改”。
- 拒绝发送 `REJECT` 与空 Map。

所有操作使用当前快照的精确 version。409 后立即重新读取 Run，不重复提交旧版本。

### Playwright 证据画廊

Review Tab 读取 Run 历史，按版本提取非空 `reviewer.screenshotDataUrl`，以版本号去重并显示
缩略图；选择缩略图后展示完整截图。相邻 DOM Tab 使用只读 Monaco Editor 呈现对应
`reviewer.dom`，并显示 `reviewer.finalUrl`、summary、feedback、model 与 error。

## 前端依赖与 Maven 构建

版本锁定为 2026-08-02 从 npm registry 与 Maven repository 读取的稳定版本：

```text
React / ReactDOM                 19.2.8
Vite                             8.2.0
TypeScript                       7.0.2
@vitejs/plugin-react             6.0.5
@monaco-editor/react             4.7.0
monaco-editor                    0.53.0
@xterm/xterm                     6.0.0
@xterm/addon-fit                 0.11.0
lucide-react                     1.28.0
parse-diff                       0.12.0
Vitest                           4.1.10
frontend-maven-plugin            2.0.2
Node.js                          22.22.2
npm                              10.9.2
```

测试依赖锁定到 `@testing-library/react 16.3.2`、`jest-dom 7.0.0`、
`user-event 14.6.1`、`jsdom 30.0.1`、`@types/react 19.2.18` 与
`@types/react-dom 19.2.4`。`package-lock.json` 必须提交。Vite build 只负责转译与打包，
不能替代 `tsc --noEmit`；两条命令都是前端门禁。

`monaco-editor` 原设计版本 0.56.0 会传递安装存在 npm 安全通告的
`dompurify@3.4.8`。0.53.0 不依赖 DOMPurify，且满足
`@monaco-editor/react@4.7.0` 声明的 `>=0.25.0 <1` peer 范围，因此固定为
0.53.0。前端依赖门禁要求 `npm audit` 的 high、critical、moderate 和 low 均为 0。

`frontend-maven-plugin` 在 `generate-resources` 安装固定 Node/npm、执行 `npm ci` 和
`npm run build`；Vite 输出到 `agent-web/target/classes/static`。前端 Vitest 在 Maven
`test` 阶段执行。`node_modules/`、coverage、Vite cache 与本地 Node 工具链加入根
`.gitignore`，构建产物不提交。

## 数据流

1. 用户提交 graphId 与初始不可变状态，REST 返回版本 0 Run。
2. 前端连接 Trace WebSocket 与终端 WebSocket，并读取 Run 历史。
3. `StateGraph` 将精确执行上下文交给节点；`OpsNode` 一边执行命令，一边发布 ANSI 日志。
4. 终端总线独立推送给 WebSocket/SSE 订阅者；Checkpoint 完成后保存完整输出。
5. 中断时工作台显示审批 Dialog；批准可携带白名单变量更新并从同一节点恢复。
6. Reviewer 将 DOM、截图和最终 URL 写入状态，历史 API 为画廊提供证据版本。
7. Trace 终态完成 Trace 与日志连接，前端最后刷新权威 Run 与历史。

## 错误与安全语义

- 所有标识符、状态键、枚举与路径只做精确匹配。
- 审批修改不得新增状态键，不得修改未公开在 Interrupt details 的键。
- WebSocket/SSE 的慢客户端只断开自身连接，不阻塞节点和其他订阅者。
- 日志发布失败保留完整堆栈到 `ops.logError`，不覆盖命令结果。
- 前端 JSON、Diff、REST、WebSocket 与 SSE 解码错误进入明确错误状态，不吞异常。
- DOM 只作为 Monaco 文本显示，不使用 `dangerouslySetInnerHTML`。
- 截图只接受 Reviewer 生成的 `data:image/png;base64,` URL。
- 不提交数据库凭据、API Key、Node 构建产物或浏览器截图产物。

## 测试策略

### Core 与节点测试

- `StateGraph` 验证上下文 runId/nodeName、虚拟线程和旧 Lambda 行为。
- `RunLogEvent` 验证不可变字段、序列和 Jackson 往返。
- `OpsNode` 验证三种流、ANSI 原样保留、序列、发布失败堆栈与最终结果。
- `AgentRunService` 验证审批变量白名单、不可变更新、非法键不追加版本和旧请求兼容。
- `ReviewerNode` 验证成功与模型失败时都保留 URL、DOM、PNG data URL。

### Web 测试

- 日志总线验证多订阅者隔离、顺序、1024 上限、单订阅溢出、终态和 close。
- WebSocket 验证快照后日志、快照窗口无丢失、ANSI、4404、终态与断连清理。
- SSE 使用 `WebTestClient` 验证精确事件名、id、frame JSON、404 和终态完成。
- REST 验证历史顺序、审批更新、400/404/409 与严格未知字段。
- 集成测试以真实 RunService 图让上下文节点发布日志，完成 start -> log -> interrupt ->
  modify/approve -> complete 闭环。

### 前端测试

- Vitest 覆盖 API 解码、Diff 转换、Run 状态 Hook、审批三条路径和 Tab 状态。
- React Testing Library 覆盖启动、状态、Timeline、Monaco/xterm 包装器与 Gallery。
- Java Playwright 在随机 Spring Boot 端口加载真实构建产物，检查桌面与移动布局、审批
  交互、Diff、ANSI 终端、截图和 DOM。
- 完成前使用 Playwright 生成桌面与移动 PNG，并进行人工截图和像素检查，确认非空、无
  重叠、文本不溢出、资源正确加载。

最终运行 JDK 21 `mvn clean verify` 与前端 `npm run test:run`、`npm run build`，汇总 Java
和前端测试，确认 Docker、Playwright、PostgreSQL 测试均实际执行且无资源遗留。

## Phase 5 边界

本阶段不实现身份认证、租户隔离、Run 列表分页、日志数据库、Redis 广播、多实例实时
通道、任意文件浏览、在线代码写回、交互式终端 stdin、Run 取消、Codebase RAG、pgvector、
MemoryManager、Langfuse、OpenTelemetry 或 Benchmark。这些能力需要独立设计或属于
Phase 6。
