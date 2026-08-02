# Agent4j 技术攻关、踩坑复盘与面试表达指南

> 证据基线：Phase 5 Task 9 前端工程里程碑（2026-08-03）。本文依据 Phase 1 至 Phase 5 的 Git
> 补丁、`docs/superpowers/specs/` 设计文档、生产代码和自动化测试整理。Phase 5
> 后端闭环已经落地；React 工作台在此基线仅完成依赖清单与构建骨架，Monaco、xterm
> 和工作台浏览器闭环仍在开发。后续 Phase 5 提交必须同步维护本文。

## 1. 项目核心攻关与避坑总览

### 1.1 为什么不用 LangChain4j/LangGraph4j

项目目标不是封装一次模型调用，而是掌握并控制 Agent 的完整运行语义：不可变状态、精确
节点路由、最大步数熔断、Checkpoint 版本、HITL 挂起与恢复、工具异常回灌、模型降级和
实时 Trace。第三方 Agent 框架会把 Loop、状态合并、恢复位置和异常传播藏在框架约定中，
也会把核心领域模型绑定到外部 API。

因此项目在 Java 21 上自研 `StateGraph`、`AgentState`、`Checkpointer`、
`AgentRunService` 和强类型事件协议，同时只在能力层使用 JavaParser、JGit、Docker-Java、
pty4j、Playwright 与 Resilience4j。这样做的直接价值是：

- 图内状态全部由不可变 record 表达，变更一定产生新实例。
- 每个节点、路由键、状态键、模型端点和恢复节点都执行精确匹配。
- 阻塞式网络、终端和浏览器等待显式放入 Java 21 虚拟线程。
- Checkpoint、实时事件和外部副作用的边界由本项目定义，不受框架隐式重试影响。
- 错误保留 cause、suppressed exception 或完整堆栈，Agent 能读取失败证据进入修复循环。

这不是为了重写所有基础设施。协议解析、补丁应用、容器控制、浏览器控制和熔断仍交给
经过验证的专用库；项目自研的是 Agent 编排与一致性内核。

### 1.2 五个最硬的踩坑点

| 攻关点 | 失效模式 | 最终防线 | 主要证据 |
|---|---|---|---|
| Playwright 线程亲和性 | 在不同异步线程创建和使用 `Page`、`BrowserContext`，触发线程安全问题 | 全生命周期绑定一个专属单线程虚拟线程执行器 | `PlaywrightBrowserService`、Phase 3 规格、真实 Chromium 测试 |
| AST 与增量补丁边界 | 路径穿越写出仓库、冲突补丁产生部分修改、错误校验顺序掩盖“非 Git 仓库”根因 | JGit `Patch` 预解析、应用前后双重根目录校验、真实 Git 冲突测试 | `73e0baa`、`72a99e0`、`AstServiceDiffTest` |
| Docker/PTY 超时与资源残留 | PTY 进程超时后继续运行；Docker 容器退出、超时或回调失败后残留 | 强杀进程/容器，Docker 在 `finally` 强制删除，清理错误进入 suppressed | `PtyCommandExecutorTest`、`DockerCommandExecutorTest` |
| Checkpoint 与实时事件一致性 | 实时事件发布失败反向破坏持久化；并发审批追加两个版本；数据库时间精度导致对象往返不等 | PostgreSQL 唯一权威源、`latest_version` 乐观锁、时间统一到微秒、发布失败不回滚 | `c70fcd5`、`JdbcCheckpointerTest`、`AgentRunServiceTest` |
| 快照与实时流竞态 | WebSocket 先读快照再订阅，快照查询期间发布的事件永久丢失；慢客户端拖垮其他连接 | 先占用有界订阅再读快照；每订阅者独立 1024 队列，溢出只关闭自身 | `0ce305c`、`b6174bb`、`2062691` |

## 2. 各阶段踩坑与技术细节深度复盘

## Phase 1：图内核、虚拟线程与 LLM SSE

### 2.1 SSE 不是“逐行 JSON”

**【问题现象】** 直接把每个 `data:` 行当作完整 JSON 时，多行 SSE 事件会解析失败；
HTTP 502、坏 JSON或日志消费者抛错如果被统一包装，还会丢失最有价值的原始异常。

**【根因分析】** SSE 以空行分隔事件，一个事件可以包含多个 `data:` 行；OpenAI 兼容
协议还使用 `[DONE]` 作为终止标记。`RestClient.exchange` 不会替业务代码完成这些协议
语义。早期实现对 SSE HTTP 错误的异常链保留不完整，修复提交 `39d88be` 为此增加了
HTTP body、多行事件、坏 JSON 和 consumer failure 回归测试。

**【解决方案/代码级实现】** `LlmClient.consumeSse` 使用 UTF-8 `BufferedReader`，只接受
精确前缀 `data:`，将同一事件的多行数据用换行连接，遇到空行才交给 Jackson；
`dispatchSseEvent` 精确识别 `[DONE]`。错误状态构造 `RestClientResponseException` 并作为
`LlmClientException` 的 cause；JSON、I/O 和 consumer 异常也保留原始 cause。

**【证据】** `LlmClientTest.streamsSseChunksAndStopsAtDoneOnVirtualThread`、
`joinsMultipleDataLinesWithinOneSseEvent`、`wrapsSseHttpErrorsAndPreservesCause`、
`preservesMalformedSseJsonCause`、`preservesSseConsumerFailureCause`。

### 2.2 为什么选 RestClient，而不是 WebClient

**【问题现象】** 图节点本身是同步的 `Node.execute` 合约，如果网络层返回 Reactor 类型，
调用链会同时承担图状态机和响应式流两套生命周期、取消和错误语义。

**【根因分析】** 本项目的并发策略是“阻塞式 SDK + Java 21 虚拟线程”，而不是把核心图
改造成响应式图。模型网关 I/O 会阻塞，但虚拟线程可以在不占用传统平台线程的情况下
等待；SSE 又需要逐事件同步调用 consumer。选择 `WebClient` 会增加桥接代码，却不能
替代项目必须自己处理的 SSE 帧边界和异常链。

**【解决方案/代码级实现】** 注入 Spring `RestClient` 和 `ObjectMapper`，同步补全与 SSE
都提交到 `Executors.newVirtualThreadPerTaskExecutor()`；调用方等待 `Future.get()`。
`InterruptedException` 会恢复中断标记，`ExecutionException` 会解包并保留 cause。
这是架构取舍，不是对 `WebClient` 性能的否定；`agent-web` 的 WebSocket/SSE 网关仍使用
WebFlux，因为网关层面对的是大量长连接和背压。

### 2.3 虚拟线程不能替代生命周期与熔断

**【问题现象】** 虚拟线程便宜，但无界 Agent 循环、关闭后继续提交、节点异常或遗留执行器
仍会耗尽资源并造成不可恢复的运行。

**【根因分析】** Loom 解决的是等待成本，不负责图终止、任务所有权和业务错误隔离。

**【解决方案/代码级实现】** `StateGraph` 为每次节点执行提交虚拟线程任务，同时使用
`maxSteps` 防止条件边无限循环；节点异常统一包装为 `GraphExecutionException` 并保留
cause；`StateGraph`、`LlmClient` 都实现 `AutoCloseable`，关闭后拒绝新任务。
Phase 4 又保证每个图实例在异步执行任务的 `finally` 中关闭。

**【证据】** `StateGraphTest.executesPlannerToolFlowOnVirtualThreads`、
`stopsLoopAtMaximumSteps`、`preservesNodeFailureCause`、`rejectsExecutionAfterClose`。

### 2.4 Windows 跨盘 Surefire 类路径

**【问题现象】** Windows 环境在 Maven 与仓库位于不同盘符时，Surefire 使用系统类加载器
导致测试类路径异常。

**【根因分析】** 问题位于测试进程类加载边界，不是 Java 源码或 JDK 版本错误。

**【解决方案/代码级实现】** `f8a23c2` 在根 POM 为
`maven-surefire-plugin` 设置 `<useSystemClassLoader>false</useSystemClassLoader>`，让
Surefire 使用隔离类加载方式。仓库 POM 始终固定 `java.version=21` 和
`maven.compiler.release=21`；执行环境也必须显式绑定 JDK 21，不能依赖终端默认 JDK。

## Phase 2：JavaParser、JGit、Docker 与 PTY

### 2.5 AST 符号提取必须有精确身份

**【问题现象】** 简单类名匹配会在同名类、内部类和不同包之间取错目标；只返回方法名又
无法区分重载，按文本偏移截取源码还会产生行号偏差。

**【根因分析】** JavaParser 的节点树提供了限定名、Range 和声明信息，但这些信息必须
完整保留下来，不能降级为字符串搜索。

**【解决方案/代码级实现】** `AstService` 使用 Java 21 parser configuration，以
`qualifiedClassName` 精确定位唯一类。`ClassInfo` 保存限定名、起止行和源码；
`MethodInfo` 保存名称、完整 declaration、起止行和源码，只提取目标类直接声明的方法，
按源码顺序返回。重载由 declaration 区分，缺失 Range 或定位不唯一时抛
`AstServiceException`。

**【证据】** `AstServiceTest.extractsClassAndDirectMethodsByQualifiedName`、
`requiresExactQualifiedClassName`、`preservesParsingFailureCause`。

### 2.6 Unified Diff 的安全顺序比调用 ApplyCommand 更重要

**【问题现象】** 恶意补丁可通过 `../` 写出仓库；上下文不匹配的补丁会产生冲突；更隐蔽
的问题是先解析补丁、后验证仓库时，普通目录加坏补丁会报“补丁非法”，掩盖真正的
“不是 Git 工作树”。

**【根因分析】** JGit `ApplyCommand` 负责 Git 补丁语义，但仓库身份、输入路径边界和错误
优先级仍由调用方负责。只在写入后检查路径已经太晚。

**【解决方案/代码级实现】** `applyDiff` 先 `toRealPath()` 并用 `Git.open` 验证传入路径
就是实际 work tree；再用 JGit `Patch` 预解析 UTF-8 补丁，对每个非 `/dev/null` 的
old/new path 做标准化并验证仍在根目录；之后才把同一字节交给 `git.apply()`。应用返回的
更新文件转为绝对规范路径后再检查一次。冲突、空补丁、路径穿越和非仓库全部失败，不做
字符串替换，也不自动提交。

`72a99e0` 专门把“验证 Git 工作树”移动到“解析补丁”之前，并新增
`validatesRepositoryBeforeParsingNonEmptyPatch`，证明错误优先级是可测试协议。

**【证据】** `AstServiceDiffTest.appliesUnifiedDiffAndReturnsUpdatedFiles`、
`rejectsConflictingPatchAndPreservesFile`、`rejectsPathTraversalBeforeWritingOutsideRepository`。

### 2.7 PTY 的 ANSI、退出码和超时是三件事

**【问题现象】** 把 PTY 当普通 `ProcessBuilder` 容易丢失 ANSI 控制序列；把非零退出码当
异常会丢掉工具的正常失败语义；只让等待超时而不杀进程会留下后台 Bash。

**【根因分析】** PTY 合并 stdout/stderr 并携带终端控制字节，命令失败与执行器故障是
不同结果，超时还要求主动治理进程生命周期。

**【解决方案/代码级实现】** `PtyCommandExecutor` 以精确传入的 Bash 路径执行
`bash -lc`，原始合并输出写入 `stdout` 并发布为 `Stream.PTY`，不剥离 ANSI；正常结束
返回真实 exit code，超时调用 `destroyForcibly()` 并返回 `exitCode=-1`、
`timedOut=true`。读取和执行位于 `SandboxTerminalService` 的虚拟线程任务中。

**【证据】** `PtyCommandExecutorTest.preservesAnsiControlSequences`、
`returnsNonZeroExitCodeWithoutException`、`terminatesProcessAtTimeout`。

### 2.8 Docker 一次性容器的清理必须覆盖所有出口

**【问题现象】** 如果只在成功路径清理，非零退出、超时、日志 consumer 抛错或 Docker
API 抛错都会绕过普通清理逻辑，留下容器和工作区挂载。

**【根因分析】** Docker 创建、启动、日志等待、退出码查询和删除是多阶段外部资源协议；
只在成功路径调用 remove 不完整，清理异常覆盖主异常又会丢失首要故障。

**【解决方案/代码级实现】** `DockerCommandExecutor` 创建带
`com.agent.runtime.managed=true` 标签的一次性容器，将工作区读写绑定后执行
`bash -lc`。超时先终止容器；无论结果如何都在 `finally` 强制删除。已有主异常时，清理
错误通过 `addSuppressed` 保留；没有主异常时，清理错误成为明确失败。

**【证据】** `DockerCommandExecutorTest.capturesSeparatedLogsAndWritesMountedWorkspace`、
`returnsActualNonZeroExitCode`、`stopsAndRemovesContainerAtTimeout`，以及每个测试后的容器
标签清理断言。

### 2.9 Coder -> Ops 不能吞工具异常

**【问题现象】** 如果补丁冲突或命令 Future 异常只变成一个短错误消息，后续 Agent 无法
判断失败层级，也无法形成可靠修复循环。

**【根因分析】** 异步包装会产生 `CompletionException`/`ExecutionException` 层级，错误
回灌若只保存 `getMessage()` 会丢失类型、cause 和堆栈位置。

**【解决方案/代码级实现】** `CoderNode` 将完整堆栈写入 `coder.error`；`OpsNode` 将完整
异步异常栈写入 `ops.error`，同时使用精确状态键保存命令结果。Phase 5 的实时日志
publisher 失败另存 `ops.logError`，不丢弃已完成的命令结果。

## Phase 3：Playwright、模型路由与 Reviewer

### 2.10 Playwright 的线程亲和性死穴

**【问题现象】** 常见异步写法是每次调用 `CompletableFuture.supplyAsync`，但这会让
Playwright、Browser、Context 和 Page 在不同线程上创建、操作或关闭，产生线程安全错误
和不稳定清理。

**【根因分析】** Playwright Java 对象不是线程安全对象。虚拟线程执行器若采用
per-task 模式，也不能保证两次任务落在同一虚拟线程。

**【解决方案/代码级实现】** `PlaywrightBrowserService` 使用：

```java
Executors.newSingleThreadExecutor(
        Thread.ofVirtual().name("playwright-browser-", 0).factory())
```

Playwright、headless Chromium、Context、Page 的延迟创建、导航、点击、DOM、截图和关闭
都提交到这个专属线程。关闭顺序固定为 Page -> Context -> Browser -> Playwright；后续
清理失败加入 suppressed，关闭后所有新操作返回失败 Future。

**【证据】** `PlaywrightBrowserServiceTest` 使用真实 Chromium 验证导航、点击、DOM、
全页 PNG、超时、异步和重复关闭。

### 2.11 多模型降级必须把“响应有效性”算进熔断

**【问题现象】** 端点 HTTP 200 但 `choices` 为空或首条 message 为 null 时，如果先让
熔断器记录成功、再在外部校验响应，坏端点不会积累失败；OPEN 端点若被当成总路由失败，
又会阻止后续模型接管。

**【根因分析】** 模型可用性不仅是 HTTP 成功，还包括协议响应可消费；
`CallNotPermittedException` 表示当前端点不可调用，不表示整个 TaskType 无路可走。

**【解决方案/代码级实现】** `ModelRouter` 由构造器注入
`Map<TaskType, List<ModelEndpoint>>`，列表顺序就是主模型和降级链。对每个端点，在
`CircuitBreaker.executeSupplier` 内完成 LLM 调用和 choices/message 校验；HTTP、协议
空响应与 `CallNotPermittedException` 都包装成带精确 endpoint/model 的
`ModelEndpointException`，随后尝试下一项。全部失败时按尝试顺序放入
`ModelRoutingException` 的 suppressed 列表。

Router 不自行创建、关闭或重置熔断器，也不把模型回答“拒绝”当基础设施失败。OPEN、
HALF_OPEN 和 CLOSED 的切换完全由调用方注入的 Resilience4j 配置和实际调用结果决定。

**【证据】** `ModelRouterTest.fallsBackInOrderAfterHttpFailure`、
`skipsOpenCircuitAndUsesFallbackWithoutHttpCall`、
`recordsEmptyChoicesAsCircuitFailureAndFallsBack`、
`aggregatesOpenCircuitFailuresInEndpointOrder`。

### 2.12 Jackson 的类型强制会污染 Reviewer 决策

**【问题现象】** 模型返回 `{"approved":"true"}` 或数字型 `summary` 时，单纯反序列化
record 会触发标量类型强制，使非法协议看起来合法。

**【根因分析】** 关闭未知字段并不等于关闭所有标量 coercion；LLM 输出属于不可信边界，
必须先检查 JSON token 类型。

**【解决方案/代码级实现】** `79439e7` 在 record 反序列化前先读取 `JsonNode`，要求根节点
是 object，`approved` 精确为 boolean，`summary` 和 `feedback` 精确为 string；随后再用
配置了未知字段失败的 `ObjectReader` 映射。Markdown fence、未知字段、非文本 assistant
content 和类型错误全部进入 `reviewer.error` 完整堆栈。

**【证据】** `ReviewerNodeTest.rejectsMarkdownFenceAndUnknownJsonFields`、
`rejectsNonTextAssistantContent`、`rejectsCoercedReviewerDecisionFieldTypes`。

## Phase 4：PostgreSQL Checkpoint、HITL 与 Trace

### 2.13 PostgreSQL 是唯一权威源，事件通道不参与双写

**【问题现象】** 如果数据库 Checkpoint 和实时事件总线都被当作 Run 状态，任一发布失败
都会产生两个“最新状态”；若在同一业务路径中强制双写成功，WebSocket 故障还会回滚已
完成的节点状态。

**【根因分析】** Checkpoint 需要持久化、历史版本和恢复；实时 Trace 只负责低延迟通知，
二者可靠性和生命周期不同。

**【解决方案/代码级实现】** Phase 4 明确 PostgreSQL 是 Checkpoint 唯一权威数据源，
`AgentRunService` 先追加 Checkpoint，再发布 Trace；发布失败只记录完整服务错误，不回滚
数据库。REST 和恢复只读 `Checkpointer`，不读进程内总线。

当前代码没有引入 Redis：Phase 4/5 使用进程内 Trace/日志总线。后续若为多实例部署加入
Redis，它只能承担事件分发通道，不能保存或裁决 Run 最新状态；这正是避免应用层双写
陷阱的边界。

### 2.14 并发审批靠数据库乐观锁裁决

**【问题现象】** 两个人同时批准/拒绝同一 `WAITING_APPROVAL` Run，如果只在 JVM 内检查
版本，多个实例或并发请求会形成重复追加竞态。

**【根因分析】** “先查询最新版本，再插入下一版本”是典型 check-then-act 竞态，应用锁
无法覆盖多实例。

**【解决方案/代码级实现】** `agent_runs.latest_version` 是行级版本，`append` 在单个事务
内执行：

```sql
update agent_runs
set latest_version = latest_version + 1,
    status = :status,
    updated_at = :updatedAt
where run_id = :runId
  and latest_version = :expectedVersion
```

更新行数不是 1 时精确区分 Run 不存在与版本冲突；只有更新成功的事务才能插入下一条
`agent_checkpoints`。审批还要求最新状态精确为 `WAITING_APPROVAL`。

**【证据】** `JdbcCheckpointerTest` 的两个并发 append 只有一个成功；
`AgentRunServiceTest.rejectsStaleOrTerminalApprovalAndAllowsOnlyOneConcurrentDecision`。

### 2.15 HITL 恢复不是简单地“重新跑图”

**【问题现象】** 批准后若仍从图入口运行会重复已完成节点；若恢复时继续执行同一中断
策略，会立即再次挂起；若永久绕过策略，又会跳过后续危险节点。

**【根因分析】** 挂起点必须与状态快照一起持久化，批准只授权当前精确节点的一次执行。
节点外部副作用与 Checkpoint 之间也不具备跨系统事务。

**【解决方案/代码级实现】** `WAITING_APPROVAL` 同时保存 `nextNode` 和节点名一致的
`InterruptRequest`。APPROVE 追加新的 `RUNNING` 版本并从该精确节点恢复，
`bypassInterruptAtStart=true` 只绕过起始节点一次；后续节点恢复正常检查。REJECT 追加
`REJECTED`，不再调度。服务重启只恢复最新 `RUNNING`，不自动恢复等待审批或失败 Run。

系统明确采用至少一次节点执行语义：进程若在外部副作用完成、Checkpoint 提交前崩溃，
节点会再次执行，因此带外部副作用的节点必须由业务保证幂等，不能宣称 exactly-once。

### 2.16 `timestamptz` 精度导致对象往返不相等

**【问题现象】** Java `Instant` 可带纳秒，而 PostgreSQL `timestamptz` 以微秒保存；写入
后立即返回的 Java 对象与再次查询对象在尾部纳秒不同，集成测试比较失败。

**【根因分析】** 这是数据库物理精度与 Java 时间模型不一致，不是时区转换问题。

**【解决方案/代码级实现】** `c70fcd5` 将 `JdbcCheckpointer.databaseInstant()` 统一为
`clock.instant().truncatedTo(ChronoUnit.MICROS)`，创建、追加和数据库读取使用同一精度。
真实 PostgreSQL REST/WebSocket/HITL 闭环测试验证持久化对象与 API 视图一致。

### 2.17 WebSocket 的快照/事件窗口

**【问题现象】** 原实现先 `loadLatest(runId)`，再订阅 Trace。数据库读取期间到达的终态或
节点事件没有订阅者，会被永久丢弃，客户端只看到旧快照并持续等待。

**【根因分析】** “先快照、后流”在业务顺序上正确，但注册顺序不能照搬发送顺序。

**【解决方案/代码级实现】** `0ce305c` 新增可关闭的 `TraceSubscription`：Handler 先
`openSubscription(runId)` 占用有界通道，再读权威 Checkpoint；发送时仍用
`Flux.concat(snapshot, events)` 保证首帧是 `SNAPSHOT`。查库失败、Run 不存在、客户端
断开和正常终态都幂等关闭订阅。

**【证据】** `RunTraceWebSocketTest.buffersEventsPublishedWhileLoadingTheSnapshot` 在
`loadLatest` 期间主动发布事件，断言收到精确顺序 `SNAPSHOT`、`EVENT`。

## Phase 5：响应式长连接与 Web 工作台

### 2.18 一个共享 Sink 会让慢客户端拖累所有人

**【问题现象】** 终端输出速度高于浏览器消费速度时，无界缓冲会持续占用内存；共享有界
缓冲又会让一个慢连接导致同 Run 的所有订阅者失败。

**【根因分析】** WebSocket 与 SSE 客户端的消费速度彼此独立，背压必须以订阅者为隔离
边界，而不是以 Run 为隔离边界。

**【解决方案/代码级实现】** `InMemoryRunLogEventBus` 为每个订阅者创建独立
`Sinks.many().unicast()` 和容量 1024 的 `ArrayBlockingQueue`。发布时遍历订阅快照；
`FAIL_OVERFLOW` 只移除并完成当前慢订阅，健康订阅继续接收。`complete(runId)` 完成该
Run 全部日志流，`close()` 完成所有流并拒绝后续使用。

**【证据】** `InMemoryRunLogEventBusTest` 覆盖多订阅者顺序、1025 条溢出隔离、终态和
关闭；`RunLifecycleEventPublisherTest` 证明终态 Trace delegate 抛错时仍在 `finally`
完成日志流。

### 2.19 终端 WebSocket/SSE 必须共用一个强类型协议

**【问题现象】** 如果两种传输各自拼 JSON，字段、枚举或快照缺省值会漂移；如果先查
Checkpoint 再订阅，Phase 4 的事件窗口会在终端流中重现。

**【根因分析】** 传输方式不同，但权威快照、实时日志和清理语义完全相同。

**【解决方案/代码级实现】** `TerminalFrame` 只允许 `SNAPSHOT` 和 `LOG` 两种 record。
WebSocket 与 SSE 都先 `openSubscription(runId)`，再读最新 Checkpoint，发送顺序仍为快照
后日志；ANSI `text` 原样传输。`TerminalSnapshot` 只读取 `OpsNode` 定义的精确键，
exitCode 和 timedOut 执行严格字符串解析，非法持久化值明确失败并关闭订阅。

**【证据】** `RunTerminalControllerTest.sendsSnapshotThenBufferedLogWithExactSseMetadata`、
`terminalSnapshotUsesNullForMissingResultsAndRejectsInvalidValues`；
`RunTerminalWebSocketTest` 覆盖快照窗口、ANSI、4404、终态和断连清理。

### 2.20 HITL“修改”必须是双白名单交集

**【问题现象】** 审批接口若接受任意 Map，就能借“修改命令”写入隐藏状态键；只检查
中断详情或只检查状态变量也不足以证明该键既存在又被公开允许修改。

**【根因分析】** HITL 表单来自不可信客户端，允许编辑的字段必须由运行快照和中断请求
共同授权。

**【解决方案/代码级实现】** `ApprovalCommand.variableUpdates` 防御性复制；REJECT 禁止
携带更新。APPROVE 的每个键必须同时被 `interruptRequest.details().containsKey(key)` 和
`state.variables().containsKey(key)` 精确命中，随后逐项创建新 `AgentState`。任何失败都
发生在追加 Checkpoint 前，历史版本数不变；旧三参数命令保持空 Map 兼容。

**【证据】** `AgentRunServiceTest.approvesExactWhitelistedVariableUpdateWithoutMutatingWaitingState`、
`RunControllerTest` 对 `ops.command` 与 `ops.Command` 的精确区分、
`ProductWorkbenchLifecycleIntegrationTest` 的修改批准闭环。

### 2.21 Monaco Diff 与 xterm 的当前实现边界

**【问题现象】** Monaco 不能直接把 Unified Diff 当作两份完整源码；xterm 若在后端或
前端清洗字符串会丢失 ANSI。动态组件若不执行清理，会在重连和卸载后遗留 WebSocket、Terminal、
`ResizeObserver`。

**【根因分析】** Diff 需要按 file/hunk/line type 重建 original/modified；终端需要把
快照和增量映射到同一字符流；两者都有外部对象生命周期。

**【解决方案/代码级实现】** 已批准的 Task 9-11 设计固定为：用 `parse-diff` 解析每个
hunk 的 normal/add/del 行生成两侧文本，解析失败保留原始 diff；xterm 收到快照先清空
并写 stdout/stderr，收到 LOG 后原样写 `event.text`，用 `FitAddon` 与
`ResizeObserver` 适配，切换 Run 或卸载时关闭全部资源。DOM 只进入只读 Monaco，禁止
HTML 注入；截图只接受精确前缀 `data:image/png;base64,`。

在本文证据基线中，这些前端组件尚未落地，因此这里只记录已批准设计和必须验证的失败
模式，不把它们写成已解决事故。完成相关 TDD 与真实 Playwright 浏览器测试后，应补充
实际问题、提交和回归测试证据。

### 2.22 Node/jsdom 引擎与前端依赖安全冲突

**【问题现象】** 原计划固定 Node 22.14.0，但锁定的 `jsdom@30.0.1` 要求
`^22.22.2 || ^24.15.0 || >=26.0.0`，安装会产生 engine 不兼容。Task 9 首次执行
`npm ci` 后，`npm audit` 又报告 1 个 low 和 1 个 moderate：直接依赖
`monaco-editor@0.56.0` 传递安装了 `dompurify@3.4.8`。Vite 还明确警告 ESM
`vite.config.ts` 的最近 `package.json` 未声明 module 类型。

**【根因分析】** 单独选择“稳定 Node 22”不够，必须同时验证依赖锁文件中的 engines
约束；全局 Node 版本也不能代表 Maven 构建使用的版本。版本“新”也不等于依赖链安全：
0.56.0 的 DOMPurify 传递链命中了 npm 通告，而 0.53.0 不依赖 DOMPurify。
`@monaco-editor/react@4.7.0` 的 peer 范围是精确的 `>=0.25.0 <1`，因此 0.53.0
满足组件约束。

**【解决方案/代码级实现】** `c70ead3` 将设计和计划统一修正为 Node 22.22.2，npm 固定
10.9.2；`frontend-maven-plugin` 使用模块内 `.frontend` 工具链，避免依赖机器全局
Node。Task 9 将 `package.json` 声明为 ESM，并把 Monaco 固定为 0.53.0，重新生成锁文件
后要求 `npm audit` 所有严重级别为 0。`.frontend/`、`node_modules/`、coverage 和 Vite
cache 均由根 `.gitignore` 排除。前端构建接入 Maven 后还必须验证 `npm ci`、Vitest、
TypeScript 和 Vite build 四条路径。

**【验证结果】** Task 9 使用模块内 Node 22.22.2/npm 10.9.2 从锁文件执行 `npm ci`，
实际依赖树为 `@monaco-editor/react@4.7.0 -> monaco-editor@0.53.0`，不再包含 DOMPurify，
`npm audit --audit-level=low` 返回 0 vulnerabilities。显式 JDK 21 的 Maven 反应堆执行
167 个 Java 测试与 8 个 Vitest 测试，失败、错误和跳过均为 0；TypeScript 编译和 Vite
build 也独立通过。

## 3. 面试话术提炼（STAR 法则）

以下话术只陈述仓库已有证据，不使用无法验证的吞吐量或线上故障数字。面试时可以结合
对应测试名称展示工程闭环。

### 表达模板 1：高并发与线程安全

**Situation（场景）**

“在设计 Playwright 视觉审查节点时，浏览器操作要异步执行，但 Playwright Java 的
Page、Context 等对象不是线程安全的。直接把每个操作扔进普通异步线程池，会让对象跨
线程使用；换成虚拟线程 per-task 执行器也不能保证线程亲和性。”

**Task（任务）**

“我要同时满足 Java 21 虚拟线程的阻塞等待策略、Playwright 单线程亲和性、异步 API 和
确定性资源清理。”

**Action（行动）**

“我给每个 `PlaywrightBrowserService` 建立一个
`newSingleThreadExecutor(Thread.ofVirtual().factory())`。Playwright 到 Page 的创建、
导航、点击、DOM、截图和关闭全部串行提交到这一个专属虚拟线程；资源按依赖逆序关闭，
清理中的次级错误放进 suppressed。然后用真实 headless Chromium 和本地随机端口服务做
导航、点击、PNG、超时和 close 回归。”

**Result（结果）**

“服务获得了稳定的线程所有权，调用方仍拿 `CompletableFuture`，关闭后新请求会明确失败，
真实 Chromium 测试覆盖了完整生命周期。这个方案同时解决了线程安全和资源泄漏问题，
而不是靠扩大线程池掩盖竞态。”

### 表达模板 2：精准代码修改与安全

**Situation（场景）**

“Agent 修改代码时，整文件覆盖容易破坏未相关格式和用户改动；直接做字符串替换又无法
处理重载、补丁上下文冲突和路径穿越。”

**Task（任务）**

“我要实现可审计的局部修改：精确理解 Java 符号，只应用标准 Unified Diff，并保证任何
写入都不越过 Git 工作树。”

**Action（行动）**

“我用 JavaParser 按完整限定类名提取类和直接方法，把声明、起止行和源码保存为不可变
record，用完整 declaration 区分重载。补丁侧先验证真实 Git work tree，再用 JGit
`Patch` 预解析所有 old/new path，应用前做根目录边界检查，交给 `ApplyCommand` 处理
Git 语义，应用后对返回路径再检查一次。冲突测试验证原文件不变，路径穿越测试验证仓库
外文件不会创建。”

**Result（结果）**

“CoderNode 能返回实际更新文件，冲突和越界都转成带完整堆栈的 `coder.error`，后续 Agent
可以针对真实原因修复。真实临时 Git 仓库测试覆盖了成功、冲突、非仓库和越界路径。”

### 表达模板 3：高可用与熔断降级

**Situation（场景）**

“大模型端点会遇到 HTTP 429/5xx、熔断 OPEN，也会出现 HTTP 200 但返回空 choices。若只
按 HTTP 状态做降级，协议坏响应会被熔断器记为成功；若把 OPEN 当全局失败，又无法使用
备用模型。”

**Task（任务）**

“我要构建 TaskType 感知、顺序确定、可测试且不绑定模型供应商的降级链，同时保留每个
端点失败证据。”

**Action（行动）**

“我通过构造器注入每个 `TaskType` 的 `List<ModelEndpoint>`，列表顺序定义主模型和后备
模型。每个端点的调用和 choices/message 有效性检查都放在
`CircuitBreaker.executeSupplier` 内；HTTP、协议失败和
`CallNotPermittedException` 都记录精确 endpoint/model 后继续下一项。全部失败时按尝试
顺序把各端点异常加入最终异常的 suppressed 列表。”

**Result（结果）**

“真实 `LlmClient` 配合 Mock HTTP 服务的测试证明：三种 TaskType 精确隔离、主模型失败
后顺序降级、OPEN 端点不发 HTTP 请求、空 choices 会计入熔断失败，而且最终错误保留整条
失败链。路由核心不依赖 Spring 配置，也不写死任何供应商。”

## 4. 证据索引与持续维护规则

### 4.1 关键修复提交

| 提交 | 修复内容 | 回归证据 |
|---|---|---|
| `f8a23c2` | Windows 跨盘 Surefire 类路径 | 根 POM `useSystemClassLoader=false` |
| `39d88be` | SSE HTTP、JSON、consumer 异常链 | `LlmClientTest` 五类异常/帧测试 |
| `72a99e0` | 先验证 Git 工作树再解析补丁 | `validatesRepositoryBeforeParsingNonEmptyPatch` |
| `79439e7` | Reviewer 决策字段严格 JSON 类型 | `rejectsCoercedReviewerDecisionFieldTypes` |
| `c70fcd5` | PostgreSQL `timestamptz` 微秒精度 | 真实 PostgreSQL 生命周期集成测试 |
| `0ce305c` | WebSocket 快照读取期间不丢事件 | `buffersEventsPublishedWhileLoadingTheSnapshot` |
| `c70ead3` | Node 与 jsdom engines 兼容 | 设计、计划和本地工具链统一到 22.22.2 |

### 4.2 持续维护门禁

Phase 5 后续每个里程碑提交前，都要同步检查本文：

1. 新失败是否有可复现测试、根因和修复提交。
2. 已批准设计是否已经落地；未落地内容不得改写成完成状态。
3. 新增状态键、JSON 字段、路径和枚举是否引用生产代码中的精确名称。
4. 前端完成后补充 Monaco、xterm、HITL 和视觉画廊的真实浏览器问题与截图测试证据。
5. 全量验收后补充 Java、Vitest、Vite、Docker、PostgreSQL、PTY 和 Chromium 的实际执行
   结果，不引用过期测试计数。
