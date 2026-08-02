# Phase 5 Product Workbench Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在既有 Run/Checkpoint/HITL/Trace 生命周期上提供实时终端日志、可修改审批、Reviewer 证据留存，以及随 `agent-web` 同包发布的 React 工作台。

**Architecture:** `agent-core` 只新增执行上下文和实时日志端口；`agent-web` 用进程内有界总线适配 WebSocket/SSE，并继续以 Checkpoint 为权威状态。React 工作台通过精确的同源协议呈现 Monaco Diff、xterm ANSI 日志、HITL 和 Playwright 证据，不建立第二套 Run 生命周期。

**Tech Stack:** Java 21、Spring Boot 3.3.13、WebFlux、PostgreSQL Checkpointer、React 19.2.8、TypeScript 7.0.2、Vite 8.2.0、Monaco Editor 0.56.0、xterm.js 6.0.0、Vitest 4.1.10、Playwright for Java 1.61.0。

---

## 文件结构

- `agent-core/.../engine/NodeExecutionContext.java`：不可变 Run/节点执行上下文。
- `agent-core/.../trace/RunLog*.java`：框架无关的实时日志领域协议。
- `agent-core/.../engine/Node.java`、`StateGraph.java`：兼容旧 Lambda 的上下文调度。
- `agent-core/.../nodes/OpsNode.java`：发布原始终端分片并保留发布错误堆栈。
- `agent-core/.../engine/ApprovalCommand.java`、`AgentRunService.java`：审批变量白名单更新。
- `agent-core/.../nodes/ReviewerNode.java`：持久化 URL、DOM 和 PNG data URL。
- `agent-web/.../log/*`：多订阅者有界日志总线、快照与传输帧。
- `agent-web/.../trace/RunLifecycleEventPublisher.java`：组合 Trace 发布与日志终态清理。
- `agent-web/.../terminal/*`：终端 WebSocket 与 SSE 网关。
- `agent-web/.../controller/*`：历史 API 与扩展审批 DTO。
- `agent-web/src/main/frontend/*`：单页工作台、协议解码、Diff、终端、审批和证据视图。
- `agent-web/pom.xml`：固定 Node/npm 并把测试和构建纳入 Maven 生命周期。

### Task 1: 节点执行上下文

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/engine/NodeExecutionContext.java`
- Modify: `agent-core/src/main/java/com/agent/core/engine/Node.java`
- Modify: `agent-core/src/main/java/com/agent/core/engine/StateGraph.java`
- Test: `agent-core/src/test/java/com/agent/core/engine/StateGraphTest.java`

- [ ] **Step 1: 写上下文传播红灯测试**

在 `StateGraphTest` 新增测试：节点覆盖上下文重载，记录 `runId`、精确 `nodeName` 和执行线程；另保留 Lambda 节点测试。

```java
AtomicReference<NodeExecutionContext> observed = new AtomicReference<>();
AtomicBoolean virtual = new AtomicBoolean();
Node node = new Node() {
    @Override
    public AgentState execute(AgentState state) {
        throw new AssertionError("不应调用旧入口");
    }

    @Override
    public AgentState execute(NodeExecutionContext context, AgentState state) {
        observed.set(context);
        virtual.set(Thread.currentThread().isVirtual());
        return state.withTraceEntry("work");
    }
};
UUID runId = UUID.randomUUID();
GraphExecutionResult result = graph.execute(
        new GraphExecutionRequest(runId, AgentState.empty(), "work", false),
        listener);
assertThat(observed.get()).isEqualTo(new NodeExecutionContext(runId, "work"));
assertThat(virtual).isTrue();
assertThat(result).isInstanceOf(GraphExecutionResult.Completed.class);
```

- [ ] **Step 2: 运行红灯**

Run: `mvn -pl agent-core -am "-Dtest=StateGraphTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: FAIL，`NodeExecutionContext` 不存在或 `Node` 没有上下文重载。

- [ ] **Step 3: 实现最小上下文协议**

```java
public record NodeExecutionContext(UUID runId, String nodeName) {
    public NodeExecutionContext {
        Objects.requireNonNull(runId, "runId 不能为空");
        if (nodeName == null || nodeName.isBlank()) {
            throw new IllegalArgumentException("nodeName 不能为空");
        }
    }
}
```

`Node` 增加默认方法，`StateGraph.executeNode` 接收 `runId` 并调用：

```java
default AgentState execute(NodeExecutionContext context, AgentState state) throws Exception {
    Objects.requireNonNull(context, "context 不能为空");
    return execute(state);
}

Future<AgentState> future = executor.submit(() -> node.execute(
        new NodeExecutionContext(runId, nodeName), state));
```

- [ ] **Step 4: 运行绿灯与模块回归**

Run: `mvn -pl agent-core -am test`

Expected: PASS，旧 Lambda 行为与上下文测试同时通过。

- [ ] **Step 5: 提交**

```text
feat(core): 传递节点运行上下文
```

### Task 2: 实时日志领域协议与 Ops 发布

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/trace/RunLogStream.java`
- Create: `agent-core/src/main/java/com/agent/core/trace/RunLogEvent.java`
- Create: `agent-core/src/main/java/com/agent/core/trace/RunLogPublisher.java`
- Modify: `agent-core/src/main/java/com/agent/core/nodes/OpsNode.java`
- Create: `agent-core/src/test/java/com/agent/core/trace/RunLogEventTest.java`
- Modify: `agent-core/src/test/java/com/agent/core/nodes/OpsNodeTest.java`

- [ ] **Step 1: 写模型和三流发布红灯测试**

```java
List<RunLogEvent> events = new CopyOnWriteArrayList<>();
OpsNode node = new OpsNode(executor, target, Duration.ofSeconds(2), events::add);
AgentState result = node.execute(
        new NodeExecutionContext(runId, "ops"),
        AgentState.empty().withVariable(OpsNode.COMMAND_KEY, "printf ok"));
assertThat(events).extracting(RunLogEvent::stream)
        .containsExactly(RunLogStream.STDOUT, RunLogStream.STDERR, RunLogStream.PTY);
assertThat(events).extracting(RunLogEvent::sequence).containsExactly(0L, 1L, 2L);
assertThat(events.get(2).text()).isEqualTo("\u001b[32mok\u001b[0m");
assertThat(result.variables()).containsEntry(OpsNode.EXIT_CODE_KEY, "0");
```

另测试 publisher 抛出异常时命令仍完成且 `ops.logError` 包含完整异常类、消息和堆栈。

- [ ] **Step 2: 运行红灯**

Run: `mvn -pl agent-core -am "-Dtest=RunLogEventTest,OpsNodeTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: FAIL，新协议、构造器和 `ops.logError` 尚不存在。

- [ ] **Step 3: 实现最小日志协议**

```java
public enum RunLogStream { STDOUT, STDERR, PTY }

public record RunLogEvent(UUID eventId, UUID runId, String nodeName,
        long sequence, RunLogStream stream, String text, Instant occurredAt) {
    public RunLogEvent {
        Objects.requireNonNull(eventId, "eventId 不能为空");
        Objects.requireNonNull(runId, "runId 不能为空");
        if (nodeName == null || nodeName.isBlank()) throw new IllegalArgumentException("nodeName 不能为空");
        if (sequence < 0) throw new IllegalArgumentException("sequence 不能小于 0");
        Objects.requireNonNull(stream, "stream 不能为空");
        Objects.requireNonNull(text, "text 不能为空");
        Objects.requireNonNull(occurredAt, "occurredAt 不能为空");
    }
}

@FunctionalInterface
public interface RunLogPublisher {
    void publish(RunLogEvent event);
    static RunLogPublisher noop() { return ignored -> { }; }
}
```

`OpsNode` 四参数构造器保存 publisher，上下文重载把 `TerminalLog` 显式映射并逐条发布；捕获发布异常到一个 `AtomicReference<Throwable>`，命令结束后将完整堆栈写入 `ops.logError`。旧三参数构造器委托 `RunLogPublisher.noop()`。

- [ ] **Step 4: 运行绿灯与回归**

Run: `mvn -pl agent-core -am test`

Expected: PASS。

- [ ] **Step 5: 提交**

```text
feat(core): 发布运行终端日志
```

### Task 3: 可修改 HITL 审批

**Files:**
- Modify: `agent-core/src/main/java/com/agent/core/engine/ApprovalCommand.java`
- Modify: `agent-core/src/main/java/com/agent/core/engine/AgentRunService.java`
- Modify: `agent-core/src/test/java/com/agent/core/engine/AgentRunServiceTest.java`

- [ ] **Step 1: 写审批白名单红灯测试**

```java
ApprovalCommand command = new ApprovalCommand(
        ApprovalDecision.APPROVE, waiting.version(), "已检查",
        Map.of("ops.command", "mvn verify"));
RunCheckpoint approved = service.decide(runId, command);
assertThat(approved.state().variables()).containsEntry("ops.command", "mvn verify");
assertThat(waiting.state().variables()).containsEntry("ops.command", "mvn test");
```

分别验证键不在 `InterruptRequest.details()`、键不在 `state.variables()`、`REJECT` 携带更新、空白键和 null 值全部失败且 Checkpoint 历史长度不变；三参数构造器仍产生空 Map。

- [ ] **Step 2: 运行红灯**

Run: `mvn -pl agent-core -am "-Dtest=AgentRunServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: FAIL，四参数命令不存在。

- [ ] **Step 3: 实现不可变命令与严格校验**

```java
public ApprovalCommand {
    Objects.requireNonNull(decision, "decision 不能为空");
    if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion 不能小于 0");
    requireText(reason, "reason");
    variableUpdates = Map.copyOf(Objects.requireNonNull(variableUpdates, "variableUpdates 不能为空"));
    variableUpdates.forEach((key, value) -> {
        requireText(key, "variableUpdates key");
        Objects.requireNonNull(value, "variableUpdates value 不能为空");
    });
    if (decision == ApprovalDecision.REJECT && !variableUpdates.isEmpty()) {
        throw new IllegalArgumentException("REJECT 不允许 variableUpdates");
    }
}
```

`AgentRunService.decide` 在任何 append 之前按两个 Map 的 `containsKey` 精确校验，再从等待状态逐项调用 `withVariable` 构造新状态。

- [ ] **Step 4: 运行绿灯与回归**

Run: `mvn -pl agent-core -am test`

Expected: PASS。

- [ ] **Step 5: 提交**

```text
feat(core): 支持受控审批变量更新
```

### Task 4: Reviewer 证据持久化

**Files:**
- Modify: `agent-core/src/main/java/com/agent/core/nodes/ReviewerNode.java`
- Modify: `agent-core/src/test/java/com/agent/core/nodes/ReviewerNodeTest.java`

- [ ] **Step 1: 写成功和模型失败证据红灯测试**

```java
assertThat(result.variables())
        .containsEntry(ReviewerNode.FINAL_URL_KEY, PAGE_URI.toString())
        .containsEntry(ReviewerNode.DOM_KEY, "<html><body>ready</body></html>")
        .containsEntry(ReviewerNode.SCREENSHOT_DATA_URL_KEY,
                "data:image/png;base64,AQID");
```

让 `ModelRouter` 抛出固定异常，断言以上三个键仍保留且 `reviewer.error` 包含完整堆栈。

- [ ] **Step 2: 运行红灯**

Run: `mvn -pl agent-core -am "-Dtest=ReviewerNodeTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: FAIL，三个常量和证据状态尚不存在。

- [ ] **Step 3: 先构造证据状态再调用模型**

新增精确常量 `reviewer.finalUrl`、`reviewer.dom`、`reviewer.screenshotDataUrl`。浏览器三项操作成功后构造：

```java
AgentState evidenceState = state
        .withVariable(FINAL_URL_KEY, navigation.finalUrl().toString())
        .withVariable(DOM_KEY, dom)
        .withVariable(SCREENSHOT_DATA_URL_KEY, imageUrl);
```

模型成功结果和异常堆栈都从 `evidenceState` 继续派生。

- [ ] **Step 4: 运行绿灯与回归**

Run: `mvn -pl agent-core -am test`

Expected: PASS。

- [ ] **Step 5: 提交**

```text
feat(core): 保留审查浏览器证据
```

### Task 5: 多订阅者日志总线与生命周期组合

**Files:**
- Create: `agent-web/src/main/java/com/agent/web/log/InMemoryRunLogEventBus.java`
- Create: `agent-web/src/main/java/com/agent/web/log/RunLogSubscription.java`
- Create: `agent-web/src/main/java/com/agent/web/trace/RunLifecycleEventPublisher.java`
- Create: `agent-web/src/test/java/com/agent/web/log/InMemoryRunLogEventBusTest.java`
- Create: `agent-web/src/test/java/com/agent/web/trace/RunLifecycleEventPublisherTest.java`

- [ ] **Step 1: 写隔离、溢出与终态红灯测试**

用 Reactor `StepVerifier` 验证同一 Run 两个订阅者按序收到独立事件；向只请求 1 条的订阅者发布 1025 个事件后只完成该订阅，正常订阅者继续；`complete(runId)` 和 `close()` 完成对应流。组合 publisher 必须先发布 Trace，并在 `COMPLETED`、`FAILED`、`REJECTED` 后完成日志。

- [ ] **Step 2: 运行红灯**

Run: `mvn -pl agent-web -am "-Dtest=InMemoryRunLogEventBusTest,RunLifecycleEventPublisherTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: FAIL，日志总线与组合 publisher 不存在。

- [ ] **Step 3: 实现每订阅者 1024 有界队列**

`InMemoryRunLogEventBus.subscribe(UUID)` 使用 `Flux.defer` 注册独立订阅；
`openSubscription(UUID)` 立即注册并返回公开的 `RunLogSubscription`，其 `events()` 在终止时
幂等关闭。每个订阅内部采用：

```java
Sinks.Many<RunLogEvent> sink = Sinks.many().unicast()
        .onBackpressureBuffer(new ArrayBlockingQueue<>(1024));
```

`publish` 遍历该 Run 的订阅快照；`FAIL_OVERFLOW` 只移除并完成对应订阅。
`RunLifecycleEventPublisher.publish` 使用 `try/finally`，终态类型精确判断后调用
`logBus.complete(runId)`。

- [ ] **Step 4: 运行绿灯与 Web 模块回归**

Run: `mvn -pl agent-web -am test`

Expected: PASS。

- [ ] **Step 5: 提交**

```text
feat(web): 建立运行日志事件总线
```

### Task 6: 历史、审批和终端快照 REST

**Files:**
- Modify: `agent-core/src/main/java/com/agent/core/engine/AgentRunService.java`
- Modify: `agent-web/src/main/java/com/agent/web/controller/ApprovalRequest.java`
- Modify: `agent-web/src/main/java/com/agent/web/controller/RunController.java`
- Modify: `agent-web/src/main/java/com/agent/web/controller/RunExceptionHandler.java`
- Create: `agent-web/src/main/java/com/agent/web/terminal/TerminalSnapshot.java`
- Create: `agent-web/src/main/java/com/agent/web/terminal/TerminalFrame.java`
- Create: `agent-web/src/main/java/com/agent/web/terminal/RunTerminalController.java`
- Modify: `agent-web/src/test/java/com/agent/web/controller/RunControllerTest.java`
- Create: `agent-web/src/test/java/com/agent/web/terminal/RunTerminalControllerTest.java`

- [ ] **Step 1: 写 REST 协议红灯测试**

覆盖 `GET /api/runs/{runId}/history` 升序结果与 404；审批省略/携带 `variableUpdates`、未知字段 400、非法更新 400；日志 SSE 首事件 `snapshot`、后续 `log`、精确 id 和不存在 Run 404。

- [ ] **Step 2: 运行红灯**

Run: `mvn -pl agent-web -am "-Dtest=RunControllerTest,RunTerminalControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: FAIL，新路径和 DTO 字段不存在。

- [ ] **Step 3: 实现精确 REST 模型**

`AgentRunService.history(UUID)` 映射 `checkpointer.loadHistory`，空列表抛 `RunNotFoundException`。`ApprovalRequest` 增加：

```java
Map<String, String> variableUpdates

public ApprovalRequest {
    variableUpdates = variableUpdates == null ? Map.of() : Map.copyOf(variableUpdates);
}
```

`RunExceptionHandler` 将 `IllegalArgumentException` 映射 400。`TerminalSnapshot.from(RunCheckpoint)` 只读取 `OpsNode` 常量指定的精确键并严格使用 `Integer.parseInt` 与 `Boolean.parseBoolean` 前的 `"true"/"false"` 检查。

SSE 方法先调用 `openSubscription(runId)`，再读取快照并返回
`Flux.concat(snapshot, logs)`；事件名固定 `snapshot`/`log`，data 均为 `TerminalFrame`。
不存在 Run、取消、序列化错误和终态均关闭订阅。

- [ ] **Step 4: 运行绿灯与回归**

Run: `mvn -pl agent-web -am test`

Expected: PASS。

- [ ] **Step 5: 提交**

```text
feat(web): 扩展运行查询与审批接口
```

### Task 7: 终端 WebSocket

**Files:**
- Create: `agent-web/src/main/java/com/agent/web/terminal/RunTerminalWebSocketHandler.java`
- Create: `agent-web/src/main/java/com/agent/web/terminal/TerminalWebSocketConfiguration.java`
- Create: `agent-web/src/test/java/com/agent/web/terminal/RunTerminalWebSocketTest.java`

- [ ] **Step 1: 写快照窗口与清理红灯测试**

仿照 `RunTraceWebSocketTest` 启动随机端口，验证首帧 `SNAPSHOT`，快照读取期间发布的 ANSI `LOG` 不丢失，Run 不存在关闭码 4404，终态和客户端断开后订阅数量归零。

- [ ] **Step 2: 运行红灯**

Run: `mvn -pl agent-web -am "-Dtest=RunTerminalWebSocketTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: FAIL，Handler 与路由不存在。

- [ ] **Step 3: 实现先订阅后读快照的 Handler**

```java
RunLogSubscription subscription = eventBus.openSubscription(runId);
RunCheckpoint checkpoint = checkpointer.loadLatest(runId).orElse(null);
Flux<TerminalFrame> frames = Flux.concat(
        Mono.just(TerminalFrame.snapshot(TerminalSnapshot.from(checkpoint))),
        subscription.events().map(TerminalFrame::log));
return session.send(frames.map(frame -> session.textMessage(writeJson(frame))))
        .doFinally(signal -> subscription.close());
```

配置精确映射 `/ws/runs/{runId}/terminal`，序列化、读库或不存在 Run 路径均关闭订阅。

- [ ] **Step 4: 运行绿灯与回归**

Run: `mvn -pl agent-web -am test`

Expected: PASS。

- [ ] **Step 5: 提交**

```text
feat(web): 推送实时终端 WebSocket
```

### Task 8: Phase 5 后端闭环

**Files:**
- Modify: `agent-web/src/main/java/com/agent/web/config/HarnessConfiguration.java`
- Create: `agent-web/src/test/java/com/agent/web/ProductWorkbenchLifecycleIntegrationTest.java`

- [ ] **Step 1: 写真实 RunService 闭环红灯测试**

注册精确测试图，使上下文节点发布 ANSI 日志，在 `ops` 前中断并公开 `ops.command`；通过 REST 启动，订阅日志，提交修改批准，等待 `COMPLETED`，再断言历史中保留新命令与最终状态。

- [ ] **Step 2: 运行红灯**

Run: `mvn -pl agent-web -am "-Dtest=ProductWorkbenchLifecycleIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: FAIL，生产装配尚未把同一 `RunLogPublisher` 注入图节点或生命周期发布器。

- [ ] **Step 3: 完成生产装配**

`HarnessConfiguration` 新增 `InMemoryRunLogEventBus`、`RunLifecycleEventPublisher` Bean，
并让 `AgentRunService` 注入组合 publisher。`RunLogPublisher` Bean 使用同一个日志总线实例，
供 `GraphFactory` 精确注入 `OpsNode`；测试图直接注入该端口，不从字符串推断节点或图。

- [ ] **Step 4: 运行绿灯与后端全量测试**

Run: `mvn -pl agent-core,agent-web -am test`

Expected: PASS。

- [ ] **Step 5: 提交**

```text
test(web): 验证工作台运行闭环
```

### Task 9: 前端工程、协议与 Diff

**Files:**
- Modify: `.gitignore`
- Modify: `agent-web/pom.xml`
- Create: `agent-web/src/main/frontend/package.json`
- Create: `agent-web/src/main/frontend/package-lock.json`
- Create: `agent-web/src/main/frontend/tsconfig.json`
- Create: `agent-web/src/main/frontend/vite.config.ts`
- Create: `agent-web/src/main/frontend/index.html`
- Create: `agent-web/src/main/frontend/src/api/contracts.ts`
- Create: `agent-web/src/main/frontend/src/api/runApi.ts`
- Create: `agent-web/src/main/frontend/src/diff/unifiedDiff.ts`
- Create: `agent-web/src/main/frontend/src/test/setup.ts`
- Create: `agent-web/src/main/frontend/src/api/runApi.test.ts`
- Create: `agent-web/src/main/frontend/src/diff/unifiedDiff.test.ts`

- [ ] **Step 1: 添加前端清单并生成锁文件**

`package.json` 固定设计文档全部版本，scripts 精确为 `dev`、`build`、`test:run`。运行 `npm install --package-lock-only` 生成并提交 lock；`.gitignore` 增加 `node_modules/`、`coverage/`、`.vite/`、`.frontend/`。

- [ ] **Step 2: 写协议解码和 Diff 红灯测试**

```ts
expect(decodeRunView(json).status).toBe('WAITING_APPROVAL')
expect(() => decodeRunView({...json, status: 'waiting'})).toThrow()
expect(parseUnifiedDiff(diff)[0]).toMatchObject({
  path: 'src/App.java', original: 'old\n', modified: 'new\n'
})
```

- [ ] **Step 3: 运行红灯**

Run: `npm run test:run -- src/api/runApi.test.ts src/diff/unifiedDiff.test.ts`

Workdir: `agent-web/src/main/frontend`

Expected: FAIL，模块不存在。

- [ ] **Step 4: 实现严格协议与 Diff 转换**

定义精确 union：

```ts
export type RunStatus = 'RUNNING' | 'WAITING_APPROVAL' | 'COMPLETED' | 'REJECTED' | 'FAILED'
export type TerminalFrame =
  | { kind: 'SNAPSHOT'; terminal: TerminalSnapshot }
  | { kind: 'LOG'; event: RunLogEvent }
```

所有 JSON 入口逐字段检查类型和枚举；不改写键。`parseUnifiedDiff` 调用 `parse-diff`，按 hunk 行的 `normal/add/del` 精确产生原始和修改文本，解析失败返回带原始 diff 的强类型错误。

- [ ] **Step 5: Maven 接入并运行绿灯**

`frontend-maven-plugin:2.0.2` 固定 Node `v22.14.0`、npm `10.9.2`，在 `generate-resources` 执行 `npm ci` 与 `npm run build`，在 `test` 执行 `npm run test:run`。Vite `outDir` 固定为 `../../../target/classes/static`。

Run: `npm run test:run`

Run: `npm run build`

Expected: 两条命令均 PASS。

- [ ] **Step 6: 提交**

```text
build(web): 集成工作台前端构建
```

### Task 10: Run 状态 Hook 与实时连接

**Files:**
- Create: `agent-web/src/main/frontend/src/hooks/useRunWorkbench.ts`
- Create: `agent-web/src/main/frontend/src/hooks/useRunWorkbench.test.tsx`
- Create: `agent-web/src/main/frontend/src/terminal/TerminalSession.ts`
- Create: `agent-web/src/main/frontend/src/terminal/TerminalSession.test.ts`

- [ ] **Step 1: 写启动、重连、409 和资源清理红灯测试**

使用注入的 `fetch`/`WebSocket` 工厂验证创建 Run 后读取历史并打开两条 WS；审批 409 时只重读最新 Run；切换 Run 和卸载时关闭旧 socket；终态收到后刷新最新状态与历史。

- [ ] **Step 2: 运行红灯**

Run: `npm run test:run -- src/hooks/useRunWorkbench.test.tsx src/terminal/TerminalSession.test.ts`

Expected: FAIL，Hook 与会话不存在。

- [ ] **Step 3: 实现单一状态协调器**

Hook 暴露 `run`、`history`、`traceEvents`、`connectionState`、`error`、`start`、`reload`、`decide`。URL 只由精确 `runId` 构造。`TerminalSession` 解码帧后把 `snapshot.stdout`、`snapshot.stderr`、实时 `event.text` 原样交给回调，不剥离 ANSI。

- [ ] **Step 4: 运行绿灯与前端回归**

Run: `npm run test:run`

Expected: PASS。

- [ ] **Step 5: 提交**

```text
feat(web): 协调工作台运行状态
```

### Task 11: Monaco、xterm、HITL 与证据工作台

**Files:**
- Create: `agent-web/src/main/frontend/src/main.tsx`
- Create: `agent-web/src/main/frontend/src/App.tsx`
- Create: `agent-web/src/main/frontend/src/styles.css`
- Create: `agent-web/src/main/frontend/src/components/RunLauncher.tsx`
- Create: `agent-web/src/main/frontend/src/components/CodeDiffPanel.tsx`
- Create: `agent-web/src/main/frontend/src/components/TerminalPanel.tsx`
- Create: `agent-web/src/main/frontend/src/components/ApprovalDialog.tsx`
- Create: `agent-web/src/main/frontend/src/components/ReviewEvidencePanel.tsx`
- Create: `agent-web/src/main/frontend/src/components/TraceTimeline.tsx`
- Create: `agent-web/src/main/frontend/src/components/Workbench.test.tsx`
- Create: `agent-web/src/test/java/com/agent/web/ProductWorkbenchBrowserTest.java`
- Create: `agent-web/src/test/resources/workbench/desktop-reference.json`
- Create: `agent-web/src/test/resources/workbench/mobile-reference.json`

- [ ] **Step 1: 写关键交互红灯测试**

React Testing Library 验证精确 `graphId`/JSON 启动、三个 Tab、Diff 文件选择、终端容器、七种 Trace、批准/修改/拒绝 payload、画廊版本切换、DOM 文本与非法截图拒绝。

同一步先写 `ProductWorkbenchBrowserTest`：Spring Boot 随机端口加载真实 Vite 产物，注入
固定测试图/Checkpoint 数据，验证桌面 1440x900 和移动 390x844 下启动、Diff、ANSI
终端、HITL 修改、截图、DOM、Timeline，并断言关键 bounding box 不重叠、文本位于父容器
内且截图像素非空。

- [ ] **Step 2: 运行红灯**

Run: `npm run test:run -- src/components/Workbench.test.tsx`

Run: `mvn -pl agent-web -am "-Dtest=ProductWorkbenchBrowserTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 两条命令均 FAIL，工作台组件和真实静态页面尚不存在。

- [ ] **Step 3: 实现高密度响应式界面**

使用 lucide 图标按钮与 tooltip；Monaco Diff 只读，桌面并排、窄屏 inline；xterm 创建固定尺寸终端并用 `FitAddon`/`ResizeObserver`；HITL 修改字段取 `details` 与 `state.variables` 的精确键交集；截图仅接受 `data:image/png;base64,`；DOM 只进入只读 Monaco，不使用 HTML 注入。

CSS 使用浅色中性主界面、深色终端、红绿 Diff 和琥珀审批；桌面三栏，`max-width: 900px` 时单列；按钮、Tab、终端和画廊使用固定/约束尺寸，禁止嵌套卡片和装饰渐变。

- [ ] **Step 4: 运行绿灯、类型检查和构建**

Run: `npm run test:run`

Run: `npm run build`

Run: `mvn -pl agent-web -am "-Dtest=ProductWorkbenchBrowserTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 全部 PASS，无 TypeScript、React 或 Vite 警告；真实浏览器生成临时桌面/移动 PNG
到 `target/`。

- [ ] **Step 5: 提交**

```text
feat(web): 构建 Agent 操作工作台
```

### Task 12: 截图审查与全量验证

- [ ] **Step 1: 人工检查截图与 Canvas 像素**

使用本地图片查看工具检查两张 PNG；验证页面非空、首屏构图完整、无重叠或溢出，Monaco/xterm/截图资源真实呈现。任何缺陷先添加或收紧失败断言，再按红绿循环修复。

- [ ] **Step 2: 必要缺陷按红绿循环修复**

若截图检查发现重叠、溢出、空 Canvas 或资源错误，先在 `ProductWorkbenchBrowserTest` 增加
可复现断言并确认红灯，再修改最小 CSS/组件代码，重跑该测试到绿灯。

- [ ] **Step 3: 执行完整门禁**

依次在显式 JDK 21 环境运行：

```text
java -version
mvn clean verify
npm run test:run
npm run build
git diff --check
git status --short
```

Docker、Playwright 和 PostgreSQL 集成测试在当前环境必须实际执行；检查输出中无 assumption skip，并确认没有容器、浏览器进程或临时服务遗留。

- [ ] **Step 4: 提交验收测试**

```text
test(web): 验证工作台浏览器闭环
```

- [ ] **Step 5: 完成分支并合并**

使用 `requesting-code-review` 自审差异，再使用 `finishing-a-development-branch`。确认工作树干净后，在 `D:\agent4j` 将 `master` 快进到 `feat/phase-5-product-workbench`，重新运行 JDK 21 `mvn clean verify`，最后标记 Goal complete。
