# Inference Framework Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有模型路由上实现可移植推理服务契约、端点级能力与准入预算，以及可验证的 SSE 背压指标。

**Architecture:** `ModelEndpoint` 持有不可变服务描述和独立准入控制器，`ModelRouter` 在熔断器之前完成能力与预算准入并统一处理同步/流式 fallback。`LlmClient` 继续作为 OpenAI Chat Completions 协议适配器，以同步消费者维持有界背压并返回流式指标。

**Tech Stack:** Java 21 records、虚拟线程、Spring RestClient、Apache HttpClient 5、Resilience4j、JUnit 5、AssertJ、MockRestServiceServer。

---

### Task 1: 可移植端点契约

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/llm/InferenceProtocol.java`
- Create: `agent-core/src/main/java/com/agent/core/llm/InferenceCapability.java`
- Create: `agent-core/src/main/java/com/agent/core/llm/InferenceServiceContract.java`
- Modify: `agent-core/src/main/java/com/agent/core/llm/ModelEndpoint.java`
- Create: `agent-core/src/test/java/com/agent/core/llm/InferenceServiceContractTest.java`

- [ ] **Step 1: 写契约红灯测试**

断言能力集合被冻结、空端点名/模型名/协议被拒绝，并断言现有四参数 `ModelEndpoint` 构造器生成 `OPENAI_CHAT_COMPLETIONS` 协议及全部四项能力。

- [ ] **Step 2: 运行红灯**

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -pl agent-core -am "-Dtest=InferenceServiceContractTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期因新增类型不存在而编译失败。

- [ ] **Step 3: 实现最小契约**

新增四个强类型，并让旧构造器明确使用兼容能力集合；生产 record 构造器必须冻结集合且拒绝 null 元素。

- [ ] **Step 4: 运行绿灯并提交**

运行同一测试，预期通过；提交 `feat(inference): define portable endpoint contracts`。

### Task 2: 端点并发与速率准入

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/llm/InferenceBudget.java`
- Create: `agent-core/src/main/java/com/agent/core/llm/InferenceRejectionReason.java`
- Create: `agent-core/src/main/java/com/agent/core/llm/InferenceAdmissionException.java`
- Create: `agent-core/src/main/java/com/agent/core/llm/InferencePermit.java`
- Create: `agent-core/src/main/java/com/agent/core/llm/InferenceAdmissionSnapshot.java`
- Create: `agent-core/src/main/java/com/agent/core/llm/InferenceAdmissionController.java`
- Create: `agent-core/src/test/java/com/agent/core/llm/InferenceAdmissionControllerTest.java`
- Modify: `agent-core/src/main/java/com/agent/core/llm/ModelEndpoint.java`

- [ ] **Step 1: 写准入红灯测试**

使用可控 `Clock` 精确验证：第二个并发请求在 `queueTimeout` 后以 `CONCURRENCY_LIMIT` 拒绝；一分钟窗口满时以 `RATE_LIMIT` 拒绝；时钟推进到窗口外后恢复；许可关闭两次只释放一次；快照计数准确。

- [ ] **Step 2: 运行红灯**

运行 `InferenceAdmissionControllerTest`，预期因准入类型不存在而编译失败。

- [ ] **Step 3: 实现最小准入控制器**

使用公平 `Semaphore`、受锁保护的 `ArrayDeque<Instant>` 和原子拒绝计数；不启动后台线程。每个 `ModelEndpoint` 接收独立控制器，旧构造器使用 `InferenceBudget.unlimited()`。

- [ ] **Step 4: 运行绿灯并提交**

预期准入测试通过；提交 `feat(inference): enforce endpoint admission budgets`。

### Task 3: ModelRouter 能力预检与预算 fallback

**Files:**
- Modify: `agent-core/src/test/java/com/agent/core/llm/ModelRouterTest.java`
- Modify: `agent-core/src/main/java/com/agent/core/llm/ModelRouter.java`

- [ ] **Step 1: 写路由红灯测试**

增加以下独立测试：工具请求跳过无 `TOOL_CALLING` 的主端点；视觉任务跳过无 `VISION_INPUT` 的主端点；主端点预算拒绝时 fallback；能力/预算拒绝不增加 CircuitBreaker 失败计数；全部拒绝时 suppressed 异常保持端点顺序。

- [ ] **Step 2: 运行红灯**

运行 `ModelRouterTest`，预期 HTTP 预期未满足或错误端点被调用。

- [ ] **Step 3: 实现预检与许可生命周期**

在 `circuitBreaker.executeSupplier` 之前校验能力并获取许可，在 `finally` 关闭许可；保留 `ExecutionBudgetExceededException` 立即上抛语义。

- [ ] **Step 4: 运行绿灯并提交**

预期 `ModelRouterTest` 全部通过；提交 `feat(inference): route by capability and endpoint budget`。

### Task 4: SSE 背压指标与流式路由

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/llm/StreamingMetrics.java`
- Create: `agent-core/src/main/java/com/agent/core/llm/RoutedStreamingCompletion.java`
- Modify: `agent-core/src/test/java/com/agent/core/llm/LlmClientTest.java`
- Modify: `agent-core/src/test/java/com/agent/core/llm/ModelRouterTest.java`
- Modify: `agent-core/src/main/java/com/agent/core/llm/LlmClient.java`
- Modify: `agent-core/src/main/java/com/agent/core/llm/ModelRouter.java`

- [ ] **Step 1: 写指标红灯测试**

为 `LlmClientTest` 注入可控纳秒时钟，使用真实 SSE 响应精确断言 HTTP 状态、TTFT、总耗时、chunk 数、累计消费者回调耗时和最大回调耗时；空流断言 TTFT 为空。

- [ ] **Step 2: 运行红灯**

运行 `LlmClientTest`，预期因 `StreamingMetrics` 返回契约不存在而编译失败。

- [ ] **Step 3: 实现同步背压测量**

保持 consumer 在 SSE 读取线程内执行，围绕回调累加耗时；成功日志写入指标。测试时钟构造器保持包可见，公共构造器继续使用 `System::nanoTime`。

- [ ] **Step 4: 写流式路由红灯并实现**

测试要求 `ModelRouter.stream(...)` 跳过无 `STREAMING` 能力端点、在流失败后 fallback、返回实际端点和指标；实现与 `complete(...)` 相同的能力、许可、熔断和异常聚合顺序。

- [ ] **Step 5: 运行绿灯并提交**

运行 `LlmClientTest,ModelRouterTest`，预期全部通过；提交 `feat(inference): observe streaming backpressure`。

### Task 5: Web 配置与环境契约

**Files:**
- Modify: `agent-web/src/main/java/com/agent/web/config/ModelGatewayProperties.java`
- Modify: `agent-web/src/main/java/com/agent/web/config/ModelGatewayConfiguration.java`
- Modify: `agent-web/src/main/resources/application.properties`
- Modify: `agent-web/src/test/java/com/agent/web/config/ModelGatewayPropertiesTest.java`
- Create: `agent-web/src/test/java/com/agent/web/config/ModelGatewayConfigurationTest.java`
- Modify: `.env.example`
- Modify: `README.md`

- [ ] **Step 1: 写配置红灯测试**

精确断言预算默认值、正数校验、能力集合冻结、空能力拒绝，以及各路由项获得独立 `InferenceAdmissionController` 和声明的 `InferenceServiceContract`。

- [ ] **Step 2: 运行红灯**

运行 `ModelGatewayPropertiesTest,ModelGatewayConfigurationTest`，预期因属性和端点构造尚未扩展而失败。

- [ ] **Step 3: 实现配置映射**

在 `application.properties`、`.env.example` 和 README 增加设计文档中的精确属性；配置类不得按模型名猜测能力。

- [ ] **Step 4: 运行绿灯并提交**

预期两个配置测试通过；提交 `feat(web): configure inference endpoint budgets`。

### Task 6: 第 26 章 EDD、复盘与全量验收

**Files:**
- Create: `agent-eval/src/test/java/com/agent/eval/InferenceFrameworkEddTest.java`
- Modify: `docs/ENGINEERING_PITFALLS.md`
- Modify: `docs/superpowers/plans/2026-08-09-guide-eighth-part-26-inference.md`

- [ ] **Step 1: 写 EDD 红灯**

确定性检查协议、四项能力、预算属性、能力预检顺序、流式指标字段和禁用具体推理 SDK 依赖；报告输出到 `agent-eval/target/edd/inference-framework-chapter-26.json`，并精确记录 `modelCallAttempts=0`。

- [ ] **Step 2: 运行红灯后补齐实现证据**

运行 `InferenceFrameworkEddTest` 并确认先因缺失证据失败；补齐工程复盘中的能力误报、熔断污染、无限排队和同步消费者背压问题。

- [ ] **Step 3: 运行模块与全量测试**

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -pl agent-core,agent-web,agent-eval -am test
mvn clean package -DskipTests -Dfrontend.skip=true
git diff --check
```

三条命令必须退出 `0`，并确认 Maven runtime 为 Java 21。

- [ ] **Step 4: 复核并提交**

检查 `git status`，不得包含 `.env`、日志、`target`、IDE 文件或教程参考仓库；提交 `docs(inference): record chapter 26 verification`，最后确认 `master` 工作树干净。
