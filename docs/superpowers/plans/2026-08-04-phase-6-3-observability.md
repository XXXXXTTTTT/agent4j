# Phase 6.3 Observability and Bad Case Attribution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 使用 OpenTelemetry 1.64.0 将 Run、节点、模型降级尝试与 Token 用量导出到 Langfuse OTLP，并把失败终态自动归因为 `BAD_CASE` 长期记忆。

**Architecture:** `agent-core` 只增加框架无关的模型观测端口和节点虚拟线程上下文；`agent-web` 将强类型生命周期事件与模型回调转换为显式父子 Span，并负责 HTTP/protobuf OTLP 配置；`agent-rag` 从权威 Checkpoint 构造 allowlist 证据并调用 `MemoryManager.captureBadCases`。现有 WebSocket Trace 仍是第一发布目标，任何观测失败均在其后汇总，不改变 Run 终态。

**Tech Stack:** Java 21、OpenTelemetry BOM 1.64.0、OTLP HTTP/protobuf、Langfuse v4 ingestion、Spring Boot 3.3.13、JUnit 5、AssertJ、Testcontainers PostgreSQL/pgvector。

---

## 文件结构

### agent-core

- `com.agent.core.observability.ModelCallStart`：一次精确端点尝试的上下文。
- `ModelUsage`：严格相等的 prompt/completion/total Token。
- `ModelCallSuccess`：可缺失响应模型与 usage 的成功结果。
- `ModelCallSpan`：成功、失败和关闭生命周期。
- `ModelCallObserver`：构造器注入的 span 工厂与 noop 实现。
- `NodeExecutionContext`：当前节点虚拟线程的精确 Run/节点上下文。
- `StateGraph`：在节点调用的 `try/finally` 范围内绑定/清理上下文。
- `ModelRouter`：为每个端点尝试驱动 `ModelCallSpan`。

### agent-web

- `com.agent.web.observability.OpenTelemetryRunTracePublisher`：Run/Node/Generation Span 注册表。
- `ObservabilityProperties`：`agent.observability` 精确配置协议。
- `OpenTelemetryConfiguration`：SDK、HTTP exporter、Header 与关闭语义。
- `RunLifecycleEventPublisher`：顺序发布、异常汇总和终态日志清理。
- `HarnessConfiguration`：按固定顺序组合实时、OTel 与 Bad Case 发布器。

### agent-rag

- `MemoryManager`：增加整批 `BAD_CASE` 类型门禁。
- `RunBadCaseAttributor`：终态判定、Checkpoint 读取、scope 校验、证据 allowlist 与截断。

## Task 1: OpenTelemetry 依赖与核心观测领域

**Files:**
- Modify: `pom.xml`
- Modify: `agent-web/pom.xml`
- Create: `agent-core/src/main/java/com/agent/core/observability/ModelCallStart.java`
- Create: `agent-core/src/main/java/com/agent/core/observability/ModelUsage.java`
- Create: `agent-core/src/main/java/com/agent/core/observability/ModelCallSuccess.java`
- Create: `agent-core/src/main/java/com/agent/core/observability/ModelCallSpan.java`
- Create: `agent-core/src/main/java/com/agent/core/observability/ModelCallObserver.java`
- Create: `agent-core/src/test/java/com/agent/core/observability/ModelObservationTest.java`

- [ ] **Step 1: 写领域红灯测试。** 精确断言 null、空白、负数、Token 总数不一致均被拒绝，并断言 noop span 的 `succeed`、`fail`、`close` 不抛异常。

```java
@Test
void rejectsInconsistentTokenUsage() {
    assertThatThrownBy(() -> new ModelUsage(4, 5, 8))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("totalTokens");
}

@Test
void providesNoopObserver() {
    ModelCallSpan span = ModelCallObserver.noop().start(new ModelCallStart(
            Optional.empty(), TaskType.CODE, "primary", "code-model"));
    span.succeed(new ModelCallSuccess(Optional.empty(), Optional.empty()));
    span.fail(new IllegalStateException("observed"));
    span.close();
}
```

- [ ] **Step 2: 运行红灯。**

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
mvn -pl agent-core -am "-Dtest=ModelObservationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：测试编译失败，提示 `com.agent.core.observability` 类型不存在。

- [ ] **Step 3: 增加 BOM 和最小领域实现。** 根 POM 增加
`<opentelemetry.version>1.64.0</opentelemetry.version>` 与 BOM dependency management；
`agent-web` 增加 API、SDK、OTLP exporter，测试增加 SDK testing。所有 record 使用紧凑构造器
执行设计文档中的精确校验，`ModelCallObserver.noop()` 返回无副作用 span。

- [ ] **Step 4: 运行绿灯并检查依赖树。**

```powershell
mvn -pl agent-core,agent-web -am "-Dtest=ModelObservationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -pl agent-web dependency:tree "-Dincludes=io.opentelemetry:*"
```

预期：领域测试通过；所有 `io.opentelemetry` artifact 解析为 `1.64.0`。

- [ ] **Step 5: 提交。**

```powershell
git add pom.xml agent-web/pom.xml agent-core/src/main/java/com/agent/core/observability agent-core/src/test/java/com/agent/core/observability
git commit -m "feat(observability): define model telemetry protocol"
```

## Task 2: 节点虚拟线程上下文

**Files:**
- Modify: `agent-core/src/main/java/com/agent/core/engine/NodeExecutionContext.java`
- Modify: `agent-core/src/main/java/com/agent/core/engine/StateGraph.java`
- Modify: `agent-core/src/test/java/com/agent/core/engine/StateGraphTest.java`

- [ ] **Step 1: 写上下文红灯测试。** 一个节点读取 `NodeExecutionContext.current()`，断言值等于传入 Run/节点且线程为虚拟线程；另一个节点抛异常，随后测试线程断言 `current()` 为空。

```java
Node node = new Node() {
    @Override
    public AgentState execute(AgentState state) {
        NodeExecutionContext current = NodeExecutionContext.current().orElseThrow();
        return state.withVariable("context", current.runId() + ":" + current.nodeName());
    }
};
```

- [ ] **Step 2: 运行红灯。**

```powershell
mvn -pl agent-core -am "-Dtest=StateGraphTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：测试编译失败，`NodeExecutionContext.current()` 不存在。

- [ ] **Step 3: 写最小上下文实现。** `NodeExecutionContext` 增加私有静态
`ThreadLocal<NodeExecutionContext>`、公开 `current()` 和包内 `callWithin`；嵌套绑定立即抛
`IllegalStateException`。`StateGraph.executeNode` 的 callable 通过 `callWithin` 调用节点，
`finally` 必须执行 `remove()`。

- [ ] **Step 4: 运行绿灯。**

```powershell
mvn -pl agent-core -am "-Dtest=StateGraphTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：全部 `StateGraphTest` 通过，正常和异常路径均无上下文泄漏。

- [ ] **Step 5: 提交。**

```powershell
git add agent-core/src/main/java/com/agent/core/engine/NodeExecutionContext.java agent-core/src/main/java/com/agent/core/engine/StateGraph.java agent-core/src/test/java/com/agent/core/engine/StateGraphTest.java
git commit -m "feat(core): bind node execution context"
```

## Task 3: ModelRouter 端点尝试与 Token 观测

**Files:**
- Modify: `agent-core/src/main/java/com/agent/core/llm/ModelRouter.java`
- Modify: `agent-core/src/test/java/com/agent/core/llm/ModelRouterTest.java`

- [ ] **Step 1: 写路由观测红灯测试。** 使用记录型 `ModelCallObserver`，让第一端点失败、第二端点成功并返回 `Usage(11, 7, 18)`；断言两个独立 start、第一项 fail、第二项 success/close，以及原降级结果不变。另测 `usage == null` 映射为 `Optional.empty()`。

```java
assertThat(observer.starts()).extracting(ModelCallStart::endpointName)
        .containsExactly("primary", "fallback");
assertThat(observer.successes().getSingle().usage())
        .contains(new ModelUsage(11, 7, 18));
```

- [ ] **Step 2: 运行红灯。**

```powershell
mvn -pl agent-core -am "-Dtest=ModelRouterTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：双参数构造器不存在或 observer 未收到事件。

- [ ] **Step 3: 写最小路由接线。** 保留单参数构造器并委托 noop。每次循环先构造
`ModelCallStart(NodeExecutionContext.current(), taskType, endpoint.name(), endpoint.model())`。
start/succeed/fail/close 分别使用防护方法；observer 异常通过 `System.Logger` 输出完整堆栈，
模型异常仍只进入现有 `ModelEndpointException` 链。

- [ ] **Step 4: 运行绿灯与核心回归。**

```powershell
mvn -pl agent-core -am "-Dtest=ModelRouterTest,PlannerNodeTest,ReviewerNodeTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：观测断言和既有路由/节点测试全部通过。

- [ ] **Step 5: 提交。**

```powershell
git add agent-core/src/main/java/com/agent/core/llm/ModelRouter.java agent-core/src/test/java/com/agent/core/llm/ModelRouterTest.java
git commit -m "feat(router): trace model attempts and token usage"
```

## Task 4: OpenTelemetry Run、Node 与 Generation Span

**Files:**
- Create: `agent-web/src/main/java/com/agent/web/observability/OpenTelemetryRunTracePublisher.java`
- Create: `agent-web/src/test/java/com/agent/web/observability/OpenTelemetryRunTracePublisherTest.java`

- [ ] **Step 1: 写 Span 拓扑红灯测试。** 使用 `SdkTracerProvider`、
`SimpleSpanProcessor`、`InMemorySpanExporter`，按 `NodeStarted -> ModelCall -> NodeCompleted ->
Completed` 发布，断言 3 个 Span、父 Span ID、名称和设计中的全部属性。增加 FAILED、
INTERRUPTED/APPROVED、无序事件和 close 活动 span 用例。

```java
assertThat(generation.getParentSpanId()).isEqualTo(node.getSpanId());
assertThat(node.getParentSpanId()).isEqualTo(run.getSpanId());
assertThat(generation.getAttributes().get(AttributeKey.longKey(
        "gen_ai.usage.input_tokens"))).isEqualTo(11L);
```

- [ ] **Step 2: 运行红灯。**

```powershell
mvn -pl agent-web -am "-Dtest=OpenTelemetryRunTracePublisherTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：`OpenTelemetryRunTracePublisher` 不存在。

- [ ] **Step 3: 写最小 Span 注册表。** 使用 `ConcurrentHashMap<UUID, RunSpanState>` 和每个
Run 状态的同步方法保证严格顺序。Span 使用事件时间戳；所有父关系通过显式
`Context.root().with(parentSpan)` 设置，不依赖跨线程隐式 Context。Generation span 按
`nodeContext` 精确查找活动节点，成功/失败幂等终结，第二次终结抛出状态错误。

- [ ] **Step 4: 运行绿灯。**

```powershell
mvn -pl agent-web -am "-Dtest=OpenTelemetryRunTracePublisherTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：拓扑、属性、恢复、错误与资源关闭测试全部通过。

- [ ] **Step 5: 提交。**

```powershell
git add agent-web/src/main/java/com/agent/web/observability/OpenTelemetryRunTracePublisher.java agent-web/src/test/java/com/agent/web/observability/OpenTelemetryRunTracePublisherTest.java
git commit -m "feat(observability): export run topology spans"
```

## Task 5: Langfuse OTLP HTTP 配置

**Files:**
- Create: `agent-web/src/main/java/com/agent/web/observability/ObservabilityProperties.java`
- Create: `agent-web/src/main/java/com/agent/web/observability/OpenTelemetryConfiguration.java`
- Modify: `agent-web/src/main/resources/application.properties`
- Create: `agent-web/src/test/java/com/agent/web/observability/OpenTelemetryConfigurationTest.java`

- [ ] **Step 1: 写配置红灯测试。** 使用 `ApplicationContextRunner` 验证关闭时没有 exporter；
启用但 endpoint 为空、URI 非绝对 HTTP/HTTPS、authorization 空白、timeout 非正数分别启动
失败且消息含精确属性名。使用 JDK `HttpServer` 绑定临时端口接收 `/api/public/otel/v1/traces`，
发布完整 Run 后 force flush，断言 POST、protobuf 非空正文与两个 Header。

- [ ] **Step 2: 运行红灯。**

```powershell
mvn -pl agent-web -am "-Dtest=OpenTelemetryConfigurationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：配置类型不存在。

- [ ] **Step 3: 写最小配置。** `ObservabilityProperties` 使用 record 和显式 `validate()`；
`OpenTelemetryConfiguration` 通过 `@ConditionalOnProperty(name =
"agent.observability.enabled", havingValue = "true")` 创建 HTTP exporter、固定 ingestion v4
Header、BatchSpanProcessor、Resource `service.name`、SDK 和 publisher。endpoint 原样传递，
禁止追加路径。配置关闭时由独立配置提供 noop observer。

- [ ] **Step 4: 运行绿灯。**

```powershell
mvn -pl agent-web -am "-Dtest=OpenTelemetryConfigurationTest,AgentWebApplicationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：真实本机 OTLP 请求与默认关闭应用上下文均通过。

- [ ] **Step 5: 提交。**

```powershell
git add agent-web/src/main/java/com/agent/web/observability/ObservabilityProperties.java agent-web/src/main/java/com/agent/web/observability/OpenTelemetryConfiguration.java agent-web/src/main/resources/application.properties agent-web/src/test/java/com/agent/web/observability/OpenTelemetryConfigurationTest.java
git commit -m "feat(observability): configure Langfuse OTLP export"
```

## Task 6: Trace 发布链失败隔离

**Files:**
- Modify: `agent-web/pom.xml`
- Modify: `agent-web/src/main/java/com/agent/web/trace/RunLifecycleEventPublisher.java`
- Modify: `agent-web/src/main/java/com/agent/web/config/HarnessConfiguration.java`
- Modify: `agent-web/src/test/java/com/agent/web/trace/RunLifecycleEventPublisherTest.java`
- Modify: `agent-web/src/test/java/com/agent/web/AgentWebApplicationTest.java`

- [ ] **Step 1: 写组合红灯测试。** 三个 publisher 中前两个抛不同异常，断言第三个仍收到事件，
首异常包含第二异常 suppressed；终态事件无论发布失败都调用 `logBus.complete(runId)`。应用上下文
断言固定顺序至少包含 `InMemoryTraceEventBus`。

- [ ] **Step 2: 运行红灯。**

```powershell
mvn -pl agent-web -am "-Dtest=RunLifecycleEventPublisherTest,AgentWebApplicationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：列表构造器不存在或第二个失败后第三个未执行。

- [ ] **Step 3: 写最小组合实现。** `agent-web/pom.xml` 增加对 `agent-rag` 的精确版本依赖。
构造器复制非空 publisher 列表，发布时遍历所有项，首异常为
primary，其余调用 `addSuppressed`，循环结束再抛。`HarnessConfiguration` 明确构造实时、可选
OTel、可选 Bad Case 的有序列表，禁止 Spring 按类型自动推断顺序。

- [ ] **Step 4: 运行绿灯。**

```powershell
mvn -pl agent-web -am "-Dtest=RunLifecycleEventPublisherTest,InMemoryTraceEventBusTest,RunTraceWebSocketTest,AgentWebApplicationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：发布隔离、WebSocket 和应用上下文测试全部通过。

- [ ] **Step 5: 提交。**

```powershell
git add agent-web/pom.xml agent-web/src/main/java/com/agent/web/trace/RunLifecycleEventPublisher.java agent-web/src/main/java/com/agent/web/config/HarnessConfiguration.java agent-web/src/test/java/com/agent/web/trace/RunLifecycleEventPublisherTest.java agent-web/src/test/java/com/agent/web/AgentWebApplicationTest.java
git commit -m "feat(web): compose durable trace publishers"
```

## Task 7: MemoryManager BAD_CASE 整批门禁

**Files:**
- Modify: `agent-rag/src/main/java/com/agent/rag/memory/MemoryManager.java`
- Modify: `agent-rag/src/test/java/com/agent/rag/memory/MemoryManagerTest.java`

- [ ] **Step 1: 写类型门禁红灯测试。** extractor 返回一个 `BAD_CASE` 和一个
`USER_PREFERENCE`，调用 `captureBadCases`，断言抛错且 embedding/store 调用次数均为 0；全是
`BAD_CASE` 时断言沿用 hash、embedding 和 upsert。

- [ ] **Step 2: 运行红灯。**

```powershell
mvn -pl agent-rag -am "-Dtest=MemoryManagerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：`captureBadCases` 不存在。

- [ ] **Step 3: 写最小门禁。** 现有 `capture` 与新 `captureBadCases` 委托同一私有方法；后者在
生成 `Instant`、ID、hash、embedding 之前遍历所有草稿并要求 `type == BAD_CASE`。空列表仍
返回不可变空列表。

- [ ] **Step 4: 运行绿灯与真实存储回归。**

```powershell
mvn -pl agent-rag -am "-Dtest=MemoryManagerTest,JdbcMemoryStoreIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：门禁测试和 5 项真实 PostgreSQL 记忆存储测试通过，无 skip。

- [ ] **Step 5: 提交。**

```powershell
git add agent-rag/src/main/java/com/agent/rag/memory/MemoryManager.java agent-rag/src/test/java/com/agent/rag/memory/MemoryManagerTest.java
git commit -m "feat(memory): enforce Bad Case capture type"
```

## Task 8: Run 终态 Bad Case 自动归因

**Files:**
- Create: `agent-rag/src/main/java/com/agent/rag/memory/RunBadCaseAttributor.java`
- Create: `agent-rag/src/test/java/com/agent/rag/memory/RunBadCaseAttributorTest.java`
- Create: `agent-rag/src/test/java/com/agent/rag/memory/RunBadCaseAttributorIntegrationTest.java`

- [ ] **Step 1: 写终态判定红灯测试。** 分别构造 FAILED、REJECTED、
`reviewer.approved=false`、`ops.timedOut=true`、非零 `ops.exitCode` 和四个 error key；断言每项
调用一次 `captureBadCases`。成功完成不调用。缺 scope、非法退出码、超长字段分别断言精确失败
或 4,000/20,000 截断。

```java
attributor.publish(new TraceEvent.Completed(
        eventId, runId, checkpoint.version(), occurredAt));
assertThat(captured.getSingle().repositoryId()).isEqualTo("repo-1");
assertThat(captured.getSingle().userId()).isEqualTo("user-1");
assertThat(captured.getSingle().sourceText()).contains("reviewer.approved=false");
```

- [ ] **Step 2: 运行红灯。**

```powershell
mvn -pl agent-rag -am "-Dtest=RunBadCaseAttributorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：`RunBadCaseAttributor` 不存在。

- [ ] **Step 3: 写最小归因实现。** 构造器注入 `Checkpointer` 与 `MemoryManager`。只处理设计中
三种终态，使用 `checkpointer.loadLatest(runId)` 并要求版本与事件一致；按固定顺序输出 allowlist
字段，每字段最多 4,000 code unit，最后在完整 `MemoryCapture` 20,000 上限内截断。source
开头明确要求仅返回 `BAD_CASE`。

- [ ] **Step 4: 写真实 PostgreSQL 集成测试并运行绿灯。** 使用
`pgvector/pgvector:pg16`、真实 `JdbcMemoryStore`、内存 Checkpointer、固定 extractor/embedding，
发布失败终态后查询 repository/user/type scope，断言写入、去重与其他用户隔离。

```powershell
mvn -pl agent-rag -am "-Dtest=RunBadCaseAttributorTest,RunBadCaseAttributorIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：单元测试与真实 PostgreSQL 集成测试通过，无 skip。

- [ ] **Step 5: 提交。**

```powershell
git add agent-rag/src/main/java/com/agent/rag/memory/RunBadCaseAttributor.java agent-rag/src/test/java/com/agent/rag/memory/RunBadCaseAttributorTest.java agent-rag/src/test/java/com/agent/rag/memory/RunBadCaseAttributorIntegrationTest.java
git commit -m "feat(memory): attribute failed runs as Bad Cases"
```

## Task 9: 全链路闭环、复盘与最终验收

**Files:**
- Create: `agent-web/src/test/java/com/agent/web/observability/AgentObservabilityWorkflowTest.java`
- Modify: `docs/ENGINEERING_PITFALLS.md`

- [ ] **Step 1: 写全链路红灯。** 创建真实 `AgentRunService`、`StateGraph`、
`ModelRouter`、本机模拟 OpenAI HTTP 服务、`OpenTelemetryRunTracePublisher` 与内存 span exporter；
运行节点并等待 `COMPLETED`，断言 Run -> Node -> Generation、usage、端点名称、终态和 Checkpoint
版本。让主端点失败、降级端点成功，断言两个 Generation Span。

- [ ] **Step 2: 运行红灯并只修复协议接线。**

```powershell
mvn -pl agent-web -am "-Dtest=AgentObservabilityWorkflowTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：若前八项存在接线缺口，测试以精确父 Span、属性、终态或版本断言失败；每个失败先保留
断言，再做最小生产修复，不增加 Phase 6.4 能力。

- [ ] **Step 3: 运行模块验证。**

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
mvn -pl agent-core,agent-rag,agent-web -am clean verify
```

预期：Java、真实 Docker/PTY/PostgreSQL、Spring 集成与 Vitest 全通过，失败/错误/跳过为 0。

- [ ] **Step 4: 更新工程复盘。** 在 `docs/ENGINEERING_PITFALLS.md` 增加 Phase 6.3 小节，按
【问题现象】->【根因分析】->【解决方案/代码级实现】只记录测试或命令已经证实的上下文传播、
Span 生命周期、OTLP Header、Token 空值、发布链失败隔离和 Bad Case scope/类型门禁问题。

- [ ] **Step 5: 提交闭环与复盘。**

```powershell
git add agent-web/src/test/java/com/agent/web/observability/AgentObservabilityWorkflowTest.java
git commit -m "test(observability): verify traced Agent workflow"
git add docs/ENGINEERING_PITFALLS.md
git commit -m "docs(engineering): record Phase 6.3 pitfalls"
```

- [ ] **Step 6: 最终全量验收。**

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
java -version
mvn clean verify
git diff --check
git status --short --branch
docker ps -a --filter "label=com.agent.runtime.managed=true"
```

同时运行禁止依赖扫描：

```powershell
rg -n -i "langchain4j|langgraph4j" pom.xml agent-*/pom.xml agent-*/src
```

预期：JDK 21、Maven `BUILD SUCCESS`、Java 与 Vitest 全通过、npm audit 0、真实 Docker 与
pgvector 测试无 skip、禁止依赖无匹配、无受管容器和进程残留、工作树干净。

- [ ] **Step 7: 完成分支。** 使用 `superpowers:requesting-code-review` 审查 spec/实现/测试，
修复所有证实的问题并重新全量验证；然后使用 `superpowers:finishing-a-development-branch`，
按用户既定选择本地 fast-forward 合并回 `master`，在合并结果上再次 `mvn clean verify`，只删除
Phase 6.3 worktree 和 `feat/phase-6-3-observability` 分支。
