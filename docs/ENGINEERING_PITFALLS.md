# Agent4j 技术攻关、踩坑复盘与面试表达指南

> 证据基线：第七篇 7B 真实 GUI EDD 定向验收（2026-08-09）。本文依据 Phase 1 至
> 当前分支的 Git 补丁、`docs/superpowers/specs/` 设计文档、生产代码和自动化测试整理。
> 当前证据包含真实 pgvector PostgreSQL、同仓库 single-flight、生产知识路由和六场景 EDD。

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
| Checkpoint 与实时事件一致性 | 实时事件发布失败反向破坏持久化；中断状态先可见而 `APPROVED` 事件越过 `INTERRUPTED`；并发审批追加两个版本；数据库时间精度导致对象往返不等 | PostgreSQL 唯一权威源、审批前等待当前 Run 的中断事件发布、`latest_version` 乐观锁、时间统一到微秒、发布失败不回滚 | `c70fcd5`、`AgentRunServiceTest`、`JdbcCheckpointerTest` |
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

### 2.8 Windows WinPTY 的进程树与读取线程必须分别治理

**【问题现象】** 首次全量验收中，超时用例偶发在命令已返回后无法删除 JUnit 临时工作目录；
进一步复现时，PTY 超时结果会稳定等待约 30 秒。字节码显示 pty4j 的
`WinPtyProcess.toHandle()` 不支持，直接调用 `Process.descendants()` 会抛异常；即使主进程
退出，WinPTY 输入流的原生读取也可能继续阻塞。

**【根因分析】** pty4j 的 `destroyForcibly()` 实际关闭 WinPTY 代理，不等价于 Java 原生
进程树强杀；Bash 启动的子进程可能短暂持有工作目录。0.13.12 中 `WinPtyProcess.pid()` 与
`getChildProcessId()` 返回同一 Windows PID，若不去重会对同一进程树连续执行两次 `taskkill`。
原实现还在有界 `waitFor` 之前调用 `taskkill.getInputStream().transferTo(...)`，因此所谓超时
实际上会先被无界输出读取阻塞。WinPTY 的输入流在被虚拟线程读取时，仅调用
`InputStream.close()` 也不能保证立即唤醒 Windows 原生 read。

**【解决方案/代码级实现】** `PtyCommandExecutor` 从 pty4j 明确提供的 `pid()` 和
`WinPtyProcess.getChildProcessId()` 构造包装进程与真实 Bash 子进程的
`ProcessHandle` 快照；Windows 超时路径先在 `taskkill` 前收集后代 PID，再用
`LinkedHashSet` 去重后执行一次 `taskkill /T /F`，taskkill 的 stdout/stderr 直接重定向至
`DISCARD`，随后反向强杀快照中的全部后代，使用 `onExit()` 和 1 秒上限等待进程树。
不能再调用无界的 `WinPtyProcess.waitFor()`：它等待 WinPTY 原生包装进程时
可能额外阻塞约 30 秒，因此只做 1 秒有界等待。WinPTY 输入流的原生 `read` 与 `close()`
共享读取锁，超时路径不能依赖同步关闭；Windows reader 改用 `available()` 轮询，进程销毁
后在有界 join 内退出，主流程不再创建失控的异步 closer。超时结果仍严格返回
`exitCode=-1`、`timedOut=true`，正常路径继续完整排空输出。新增工作目录释放回归测试，
并以 PTY 全类实测验证清理延迟。

超时路径还会显式关闭已交给 reader 使用的 `WinPTYInputStream`；`WinPtyProcess.destroy()`
对已标记使用中的输入流不会代为关闭，若遗漏该步骤，虚拟 reader 线程和 native 句柄会在
命令返回后继续持有工作目录。

**【证据】** `PtyCommandExecutorTest.releasesWorkingDirectoryBeforeReturningFromTimeout`、
`terminatesProcessAtTimeout`；`mvn -pl agent-sandbox test` 连续 5 次实测 `43/43` 通过，
超时用例未再出现工作目录锁定或 30 秒等待。

### 2.9 Docker 一次性容器的清理必须覆盖所有出口

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

### 2.10 Coder -> Ops 不能吞工具异常

**【问题现象】** 如果补丁冲突或命令 Future 异常只变成一个短错误消息，后续 Agent 无法
判断失败层级，也无法形成可靠修复循环。

**【根因分析】** 异步包装会产生 `CompletionException`/`ExecutionException` 层级，错误
回灌若只保存 `getMessage()` 会丢失类型、cause 和堆栈位置。

**【解决方案/代码级实现】** `CoderNode` 将完整堆栈写入 `coder.error`；`OpsNode` 将完整
异步异常栈写入 `ops.error`，同时使用精确状态键保存命令结果。Phase 5 的实时日志
publisher 失败另存 `ops.logError`，不丢弃已完成的命令结果。

## Phase 3：Playwright、模型路由与 Reviewer

### 2.11 Playwright 的线程亲和性死穴

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

### 2.12 多模型降级必须把“响应有效性”算进熔断

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

### 2.13 Jackson 的类型强制会污染 Reviewer 决策

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

### 2.14 PostgreSQL 是唯一权威源，事件通道不参与双写

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

### 2.15 并发审批靠数据库乐观锁裁决

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

### 2.15a Trace 事件顺序不能依赖 Checkpoint 观察时机

**【问题现象】** `WAITING_APPROVAL` 追加成功后，调用方可以立即提交审批；如果
`INTERRUPTED` 还在异步发布，`APPROVED` 或 `REJECTED` 会先到达实时订阅者，前端看到的
生命周期顺序与 Checkpoint 版本顺序相反。全量验收曾复现
`[APPROVED, INTERRUPTED, NODE_STARTED, NODE_COMPLETED, COMPLETED]`。

**【根因分析】** Checkpointer 的状态通知和 Trace publisher 是两条独立路径。原实现先
追加 `WAITING_APPROVAL` 再发布中断事件，`awaitStatus` 只保证持久化可见，不保证发布调用已
返回；审批入口因此能与中断发布并发。

**【解决方案/代码级实现】** `AgentRunService` 为每个 Run 注册一次性
`CompletableFuture`，再追加等待快照并发布 `TraceEvent.Interrupted`；审批/拒绝在追加和发布
自身事件前等待该 Future。Future 在发布调用返回后完成；Checkpoint 追加失败时完成异常并
移除，因而不倒置 SSOT 与事件的写入顺序，也不把事件总线当作状态存储。

**【证据】** `AgentRunServiceTest.interruptsThenApprovesAndBypassesOnlyTheInterruptedNode`
验证精确事件顺序；本次修复后的 `agent-core` 测试为 `85/85`。

### 2.16 HITL 恢复不是简单地“重新跑图”

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

### 2.17 `timestamptz` 精度导致对象往返不相等

**【问题现象】** Java `Instant` 可带纳秒，而 PostgreSQL `timestamptz` 以微秒保存；写入
后立即返回的 Java 对象与再次查询对象在尾部纳秒不同，集成测试比较失败。

**【根因分析】** 这是数据库物理精度与 Java 时间模型不一致，不是时区转换问题。

**【解决方案/代码级实现】** `c70fcd5` 将 `JdbcCheckpointer.databaseInstant()` 统一为
`clock.instant().truncatedTo(ChronoUnit.MICROS)`，创建、追加和数据库读取使用同一精度。
真实 PostgreSQL REST/WebSocket/HITL 闭环测试验证持久化对象与 API 视图一致。

### 2.18 WebSocket 的快照/事件窗口

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

### 2.19 一个共享 Sink 会让慢客户端拖累所有人

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

### 2.20 终端 WebSocket/SSE 必须共用一个强类型协议

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

### 2.21 HITL“修改”必须是双白名单交集

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

### 2.22 Monaco Diff 与 xterm 的生命周期边界

**【问题现象】** Monaco 不能直接把 Unified Diff 当作两份完整源码；xterm 若在后端或
前端清洗字符串会丢失 ANSI。动态组件若不执行清理，会在重连和卸载后遗留 WebSocket、Terminal、
`ResizeObserver`。

**【根因分析】** Diff 需要按 file/hunk/line type 重建 original/modified；终端需要把
快照和增量映射到同一字符流；两者都有外部对象生命周期。

**【解决方案/代码级实现】** 用 `parse-diff` 解析每个
hunk 的 normal/add/del 行生成两侧文本，解析失败保留原始 diff；xterm 收到快照先清空
并写 stdout/stderr，收到 LOG 后原样写 `event.text`，用 `FitAddon` 与
`ResizeObserver` 适配，切换 Run 或卸载时关闭全部资源。DOM 只进入只读 Monaco，禁止
HTML 注入；截图只接受精确前缀 `data:image/png;base64,`。

Task 11 进一步采用“编辑器面板首次按需加载、加载后保持挂载”的策略。直接在 Tab 切换时
卸载 DiffEditor 会触发 `TextModel got disposed before DiffEditorWidget model got reset`；
保持模型和 Widget 同生命周期后，既保留代码分包，也避免 Monaco 销毁竞态。终端面板始终
挂载，防止终端快照早于 ref 建立而丢失。

**【验证结果】** `Workbench.test.tsx` 覆盖三 Tab、Diff 文件、ANSI 终端容器、HITL 三条
路径、证据版本和 DOM 安全；`ProductWorkbenchBrowserTest` 使用真实 Chromium 验证 Monaco
可视行、xterm ANSI、320 像素宽 PNG、桌面/移动布局和 `pageErrors` 为空。

### 2.23 Node/jsdom 引擎与前端依赖安全冲突

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
`npm audit --audit-level=low` 返回 0 vulnerabilities。Task 9 当时的 Maven 反应堆执行
167 个 Java 测试与 8 个 Vitest 测试；Phase 5 完成后的 Task 12 计数见 4.3。TypeScript
编译和 Vite build 也独立通过。

### 2.24 Vite build 通过不代表 TypeScript 类型完整

**【问题现象】** Task 9 的 Vite build 可以在没有 React 声明包时成功；Task 10 首次新增
`useRunWorkbench` 后，独立 `tsc --noEmit` 报 `TS7016`，指出 `react` 没有声明文件，
随后 Hook 状态回调都退化为隐式 `any`。

**【根因分析】** Vite 使用转译器生成浏览器产物，不执行 TypeScript 完整语义检查；React
19 的运行包也不内置 DefinitelyTyped 声明。只把 Vite build 当编译门禁，会在没有类型
安全的情况下产出静态文件。

**【解决方案/代码级实现】** 从 npm 精确读取并锁定 `@types/react@19.2.18` 与
`@types/react-dom@19.2.4`；后者声明的 peerDependency 是
`@types/react ^19.2.0`，两者一致。Task 10 起把 `tsc --noEmit` 与 Vitest、Vite build
并列为前端门禁，并持续由 `package-lock.json` 固定传递依赖。

### 2.25 双 WebSocket 的连接所有权与跨 Run 污染

**【问题现象】** 工作台切换 Run 后，旧 Trace/终端连接若继续存活，会把旧 Run 事件写入
新页面；即使 URL 正确，服务端或代理返回的 payload runId 与连接 runId 不一致时，前端
若只解码字段类型仍会接受跨 Run 数据。审批 409 后重读最新 Run 的 GET 也会失败，若该
二次失败越过统一错误处理，界面只知道审批冲突，不知道权威状态读取失败。

**【根因分析】** WebSocket URL 隔离、payload 身份校验和 React 组件生命周期是三层独立
防线；只实现其中一层不足以保证 Run 隔离。409 处理本身又是一条新的 I/O 链，不能沿用
“冲突已处理”的假设吞掉后续异常。

**【解决方案/代码级实现】** `useRunWorkbench` 独占一个 Trace socket 和一个
`TerminalSession`，第二次 `start` 与组件卸载都会幂等关闭旧连接。连接状态直接使用浏览器
标准 `WebSocket.readyState`，不增加私有字符串协议。Trace 的 `run.runId`/
`event.runId` 和终端的 `terminal.runId`/`event.runId` 都必须与连接 runId 精确相等；
不一致时记录 Error 并关闭对应连接。审批 409 只执行一次 `GET /api/runs/{runId}`，该 GET
失败时把新异常写入 Hook error 并继续抛给调用方，不重复审批，也不刷新历史。

**【验证结果】** `useRunWorkbench.test.tsx` 与 `TerminalSession.test.ts` 覆盖创建后两条
连接、切换/卸载清理、ANSI 原样转发、409 单次重读、终态刷新、跨 Run 帧拒绝和二次 GET
失败；Task 10 当时的全量前端测试为 4 个文件、16 个测试，Task 11/12 的最终计数见 4.3。
TypeScript 与 Vite build 同时通过。

### 2.26 Vite 入口、Monaco CDN 与内部强制分包

**【问题现象】** 初版 `index.html` 未引用 `src/main.tsx`，Vite 只转换两个模块却仍返回
成功，真实页面没有“图 ID”。补入口后，`@monaco-editor/react` 默认 CDN 在离线测试中
永久显示 `Loading...`。改成本地 Monaco 后入口 chunk 达到 2.75 MB；进一步按
`codeSplitting.maxSize` 强拆 Monaco 内部模块，真实 Chromium 又在静态初始化器抛出
`TypeError: te is not a constructor`。把配置移入异步 chunk 时继续使用裸
`MonacoEnvironment`，浏览器还会抛 `ReferenceError: MonacoEnvironment is not defined`。

**【根因分析】** Vite 构建成功只证明入口图可生成，不证明入口图包含应用。Monaco loader
默认走外部 AMD 资源；本地 ESM 版本又具有大量带静态初始化顺序的内部模块，不能为消除
体积告警而任意强拆。`MonacoEnvironment` 是全局对象属性，在独立异步 chunk 中不能依赖
裸标识符解析。

**【解决方案/代码级实现】** `index.html` 显式加载 `/src/main.tsx`；`MonacoEditors.tsx`
从锁定的本地 `monaco-editor@0.53.0` 导入 editor API、Java/HTML contribution 与两个 Worker，
并按依赖 README 使用 `self.MonacoEnvironment` 后调用 `loader.config({ monaco })`。Code 和
Review 面板用 `React.lazy` 分离，主入口从 2.75 MB 降到约 222 KB；Monaco 保持单一按需
vendor chunk，Vite 告警阈值按实测 2523.67 KB 设置为 2600 KB，不再破坏内部执行顺序。

**【验证结果】** `npm run build` 转换 2494 个模块且无警告；真实 Spring Boot 静态资源测试
实际加载 editor worker 与本地 Monaco chunk，`ProductWorkbenchBrowserTest` 通过并要求
浏览器 `pageerror` 集合为空。

### 2.27 非重放终端流与权威快照的启动时序

**【问题现象】** 进程内日志总线不重放历史事件；若浏览器在终端 WebSocket 首帧
`SNAPSHOT` 到达前就批准运行，审批后的短命 Ops 日志可能在订阅尚未建立时被丢弃。反过来，
只等待界面出现审批框也不能证明终端订阅已经占用。

**【根因分析】** Checkpoint 保存最终 stdout/stderr，但实时分片与连接状态属于另一条时序；
审批可见和终端可接收不是同一个条件。

**【解决方案/代码级实现】** 终端 Handler 继续先 `openSubscription` 再读 Checkpoint；工作台
浏览器闭环在批准前条件等待 Trace 区精确显示 `PTY 已连接`，随后才提交修改审批。终态后
Hook 再并行读取最新 Run 与 history，用 PostgreSQL 权威结果覆盖短暂前端状态。

### 2.28 证据版本跟随与 Monaco 可视文本断言

**【问题现象】** Run 从 version 0 前进到 Reviewer 完成版本后，画廊选择仍可能锁在旧版本，
页面持续显示“等待 ReviewerNode 截图”。真实 Monaco 把可视空格渲染为 NBSP，直接对整个
面板 `textContent.includes("Workbench evidence")` 会错误超时，即使 `.view-line` 已显示
完整 DOM。

**【根因分析】** 证据选择是派生于权威 Run 版本的 UI 状态，不能只在首次挂载初始化；
Monaco 又是虚拟化编辑器，测试必须读取其可视行语义，不能把普通 DOM 文本假设套在上面。

**【解决方案/代码级实现】** `ReviewEvidencePanel` 在精确 `runId` 或 `version` 变化时跟随最新
版本，同时仍允许用户手动切换历史；浏览器测试等待 `.view-line`，仅在断言阶段把字符码 160
规范化为空格，不修改生产 DOM 内容。`Workbench.test.tsx` 的权威版本前进回归测试和真实
Chromium 测试共同覆盖这两层行为。

### 2.29 Maven 前端生命周期中的 worker 并发压力

**【问题现象】** 独立执行 Vitest 时 5 个文件可以通过，但第一次 `mvn clean verify` 在
固定 Node `v22.22.2` 下让默认 `forks` 池的 5 个 worker 全部等待 60 秒；切换为 5 个
`threads` worker 后，在前序 `npm ci` 与 Vite 构建完成的全量链路中仍出现同样的启动超时。

**【根因分析】** 失败发生在 worker 尚未进入测试文件之前，且单独运行与 Maven 运行的
差异只在生命周期前置步骤和并发启动数量。Windows 机器在 Java 集成测试、Node 安装依赖和
Monaco worker 构建后的可用资源窗口不足以同时启动 5 个 jsdom worker；这不是断言失败，
也不是把 Node 升级到 25 的理由。

**【解决方案/代码级实现】** `vite.config.ts` 固定 Vitest 使用隔离的 `forks` 池，但把
`maxWorkers` 设为 `1` 并关闭 `fileParallelism`。单 worker 保留进程隔离和 jsdom 语义，避免
并发启动竞态；固定的 Maven Node/npm 工具链仍为 `v22.22.2/10.9.2`。该配置下独立运行
和 Maven 生命周期均通过，前端测试仍报告精确 `5` 个文件、`22` 个测试。

**【证据】** 首次全量失败日志、固定 Node 单 fork `22/22`、最终 `mvn clean verify` 的
`agent-web` 成功结果；`npm audit --audit-level=low` 返回 `0 vulnerabilities`。

### 2.30 Phase 6.1 Codebase RAG 的向量维度、数组值语义与事务替换

**【问题现象】** 初次接入真实 `pgvector/pgvector:pg16` 时，迁移和 SQL 可以启动，但
round-trip 断言把数据库返回的 `ChildChunk` 与内存对象判定为不相等；读取
`pg_attribute.atttypmod - 4` 又把声明的 `vector(8)` 误报为 4 维。向量查询的反方向距离
还可能让 `1 - distance` 为负数，无法满足命中记录的非负分数约束。

**【根因分析】** Java record 对 `float[]` 使用引用相等，不能表达向量值语义；该镜像的
vector typmod 直接返回声明维度，不能套用其他 PostgreSQL 扩展的 typmod 偏移假设；余弦
距离的范围允许大于 1，原始相似度表达式需要显式边界处理。另一个边界是 ingest 必须先
完成全部 AST 切片和八维 embedding 校验，再调用同一个 `RagStore.replaceRepository`，否则
失败会产生半套索引。

**【解决方案/代码级实现】** `ChildChunk` 和 `RagQuery` 对数组做构造器/accessor 防御性
复制，并覆盖 `equals/hashCode` 使用 `Arrays.equals/hashCode`；JDBC 通过精确的
`atttypmod` 读取维度，SQL 用 `greatest(0.0, 1 - (embedding <=> query))` 保持非负；
迁移固定 `vector(8)`、GIN `search_vector` 和 HNSW `vector_cosine_ops`，所有 SQL 值都用
绑定参数。`CodebaseChunker` 使用 JavaParser 发现顶层/嵌套类，再调用既有 `AstService` 保留
完整限定名、重载 declaration 和起止行号；方法 symbol 固定为
`<qualifiedClassName>#<MethodInfo.declaration>`，原始声明中的前导空格也不被改写。

**【验证结果】** `JdbcRagStoreIntegrationTest` 在 Docker Engine `27.4.0` 上实际拉取并
执行 `pgvector/pgvector:pg16`，5 项测试全部通过：扩展/表/GIN/HNSW、向量和词法召回、
repository 隔离、外键失败回滚、Java fixture 两个重载方法、混合排序与同库 replace。
`Bm25ScorerTest` 3 项和 `HybridRagRetrieverTest` 3 项在 JDK `21.0.2` 下全部通过；没有
发生 assumption skip。

### 2.31 Phase 6.2 长期记忆的 scope 隔离、数组值语义与 Planner 注入

**【问题现象】** 长期记忆需要同时承载用户编码偏好、项目架构规范和历史 Bad Case；如果
只按文本或 repository 查询，用户之间会互相看到记忆，类型过滤也会失效。模型返回的
JSON 可能夹带未知字段、未知 type、null 或非字符串值。两路 PostgreSQL 查询分别把同一
条目映射成不同 Java 对象时，record 默认对 `float[]` 使用引用相等，manager 会把向量/词法
同一条目误判为不一致。Planner 还必须在模型失败时保留完整堆栈，而不能悄悄退化为空上下文。

**【根因分析】** 长期记忆与代码块索引不是同一语义单元，必须拥有独立的表、唯一键和
`repositoryId + userId + memoryType` 精确 scope；模型输出协议若不先验证字段集合，Jackson
的宽松行为会把协议漂移隐藏起来。Java record 不会自动把数组改成值语义；向量和词法 SQL
是两个 ResultSet，不能依赖对象引用或默认 record equals 做跨查询合并。Planner 的记忆端口
又位于 `agent-core`，若直接依赖 JDBC 实现会破坏核心层边界。

**【解决方案/代码级实现】** `V2__create_memory_table.sql` 使用 `rag_memories`、八维
`vector`、生成的 `tsvector`、GIN/HNSW 索引和
`unique(repository_id, user_id, memory_type, content_hash)`；`JdbcMemoryStore` 用绑定参数
执行整批 upsert，重复内容保留原 `memory_id/created_at`，任一行失败整体回滚。`MemoryEntry`
对 embedding 做构造器/accessor 防御性复制，并像 `ChildChunk` 一样覆盖
`equals/hashCode` 使用 `Arrays.equals/hashCode`。`ModelMemoryExtractor` 在路由前固定
`QUICK_CLASSIFICATION`、空 tools、temperature `0.0`，先对根节点和每个 item 做精确字段集合、
JSON 类型、枚举和 20 项上限校验；任何错误包装为带 cause 的 `MemoryExtractionException`。
`MemoryManager` 用精确 UTF-8 SHA-256 去重，向量/词法分独立 min-max 后按 `0.65/0.35` 合成，
拒绝 store 返回的外部 scope。`agent-core` 只定义不可变 `MemoryContextRequest`、
`MemoryContext` 和 `MemoryContextProvider`；`MemoryContextProviderAdapter` 在 `agent-rag`
中格式化命中，`PlannerNode` 通过构造器注入端口，在 `TaskType.CODE` Prompt 中明确当前任务
优先于不可信历史记忆，并把失败堆栈写入 `planner.error`。

**【验证结果】** JDK `21.0.2` 下 `MemoryDomainTest` 4 项、`MemoryManagerTest` 5 项、
`ModelMemoryExtractorTest` 2 项、`PlannerNodeTest` 3 项全部通过；真实 Docker Engine
`27.4.0` 上的 `JdbcMemoryStoreIntegrationTest` 5 项实际执行 `pgvector/pgvector:pg16`，
覆盖 V2 表/索引、重复 upsert、scope/type 隔离、向量/GIN 召回、批次回滚和 manager 真实
捕获闭环。`MemoryPlannerGraphTest` 验证 `PlannerNode -> CoderNode -> OpsNode` 的 Unified
Diff 修改、终端结果 `after` 和精确 trace `[planner, coder, ops]`。

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
| `6446a83` | WinPTY 超时进程树与读取线程清理 | `PtyCommandExecutorTest` 5 项超时/ANSI/退出码测试 |
| `57bcaa1` | Maven 前端测试 worker 并发受控 | Vitest 5 文件、22 测试在固定 Node 下通过 |
| `ca0b94f` | pgvector vector(8)、GIN/HNSW、事务 replace 与回滚 | `JdbcRagStoreIntegrationTest` 5 项真实容器测试 |
| `a3eb48f` | BM25 与三路混合稳定排序 | `Bm25ScorerTest` 3 项、`HybridRagRetrieverTest` 3 项 |
| `bc17890` | Java fixture ingest 到混合检索闭环 | `JdbcRagStoreIntegrationTest` 的重载、隔离和替换断言 |
| `be48c4e` | 冻结 Phase 6.2 记忆领域、V2 schema 与 Planner 注入边界 | `2026-08-03-phase-6-2-memory-design.md` |
| `973af85` | 固化 Phase 6.2 TDD 实施任务与验证门禁 | `2026-08-03-phase-6-2-memory.md` |
| `51c09b3` | 核心记忆上下文构造器注入端口 | `MemoryContextTest` 3 项 |
| `a2176ce` | 三类记忆协议与严格模型 JSON 提取 | `MemoryDomainTest`、`ModelMemoryExtractorTest` |
| `fa040f7` | V2 pgvector 记忆表、upsert、GIN/HNSW 与事务回滚 | `JdbcMemoryStoreIntegrationTest` |
| `33779ec` | MemoryManager hash、embedding、混合召回与上下文适配 | `MemoryManagerTest` 5 项 |
| `e239294` | PlannerNode 记忆 Prompt 注入与错误堆栈 | `PlannerNodeTest` 3 项 |
| `7a160b7` | 真实记忆捕获和 Planner-Coder-Ops 图闭环 | `MemoryPlannerGraphTest`、V2 manager 集成断言 |
| `09e6d72` | 记录 Phase 6.2 技术复盘 | 本节 2.31 与验收证据 |

### 4.2 持续维护门禁

Phase 5 后续每个里程碑提交前，都要同步检查本文：

1. 新失败是否有可复现测试、根因和修复提交。
2. 已批准设计是否已经落地；未落地内容不得改写成完成状态。
3. 新增状态键、JSON 字段、路径和枚举是否引用生产代码中的精确名称。
4. 前端完成后补充 Monaco、xterm、HITL 和视觉画廊的真实浏览器问题与截图测试证据。
5. 全量验收后补充 Java、Vitest、Vite、Docker、PostgreSQL、PTY 和 Chromium 的实际执行
   结果，不引用过期测试计数。

### 4.3 Phase 5 Task 12 实际验收记录

2026-08-03 在显式 JDK `21.0.2`、Docker Desktop Engine 和本机 Chromium 环境执行：

- Maven 反应堆：`agent-sandbox 37`、`agent-core 85`、`agent-web 47`，共 `169` 个 Java
  测试，失败、错误、跳过均为 `0`；`agent-rag` 无测试源。
- 前端：Vitest `5` 个文件、`22` 个测试通过；`tsc --noEmit` 通过；Vite 转换 `2494`
  个模块并成功生成静态资源；`npm audit --audit-level=low` 为 `0 vulnerabilities`。
- 集成边界：实际执行 JavaParser/JGit、Docker Bash、PTY Bash、Playwright Chromium、
  PostgreSQL Checkpoint、REST、WebSocket 和真实浏览器工作台；日志中没有 assumption skip。
- 浏览器截图：`agent-web/target/workbench/desktop.png` 与 `mobile.png` 人工检查通过，
  页面非空、Monaco/xterm/PNG/DOM 均真实呈现，无重叠或横向溢出。
- 资源清理：测试后没有发现本项目命令行、Playwright、Vitest、Surefire 或 WinPTY 残留进程；
  `docker ps -a` 未发现本项目创建的 PostgreSQL 或一次性沙箱容器。工作区中已有的其他
  Docker 容器未纳入本项目清理范围。
- 复验过程中的两个真实竞态已纳入代码与测试门禁：首次全量运行暴露 WinPTY 超时清理约
  30 秒阻塞，随后又暴露 `APPROVED` 越过 `INTERRUPTED` 的异步发布顺序；分别采用有界
  PTY reader 轮询和按 Run 等待中断事件发布修复，最终 `mvn clean verify` 于 `17:43:34`
  返回 `BUILD SUCCESS`。

### 4.4 Phase 6.1 最终验收记录

2026-08-03 在显式 JDK `21.0.2`、Docker Desktop Engine `27.4.0` 环境执行第二次
`mvn clean verify`，返回 `BUILD SUCCESS`：

- Java：`agent-sandbox 37`、`agent-core 85`、`agent-rag 20`、`agent-web 47`，共 `189`
  个测试，失败、错误、跳过均为 `0`；`pgvector/pgvector:pg16` 实际启动并执行迁移。
- RAG：父子 AST/文本切片、Unicode BM25、向量/GIN 召回、HNSW 索引、repository 隔离、
  外键失败回滚和同库替换均由真实测试覆盖；RAG 集成类没有 assumption skip。
- 前端：Maven 固定 Node `22.22.2`、npm `10.9.2`；Vite 转换 `2494` 个模块，Vitest
  `5` 个文件、`22` 个测试通过，npm audit 报告 `0 vulnerabilities`。
- 资源：验收后 `docker ps -a` 没有 `com.agent.runtime.managed=true` 容器，未发现
  `winpty-agent`、Bash、Playwright 或 Surefire 残留进程；工作区 target、node_modules 和
  `.frontend` 均由 `.gitignore` 排除。首次全量失败的一个旧 Docker 管理容器已按精确 ID
  移除，随后 clean verify 复验通过。

### 4.5 Phase 6.2 最终验收记录

2026-08-03 在隔离分支 `feat/phase-6-2-memory`、显式 JDK `21.0.2`、Maven `3.8.8` 和
Docker Desktop Engine `27.4.0` 环境执行：

- 模块级 `mvn -pl agent-core,agent-rag -am clean verify` 返回 `BUILD SUCCESS`；根目录
  `mvn clean verify` 第二次执行返回 `BUILD SUCCESS`，总耗时 `04:37`。
- Java 测试报告逐模块统计为：`agent-sandbox 37`、`agent-core 91`、`agent-rag 37`、
  `agent-web 47`，合计 `212`；失败、错误、跳过均为 `0`。新增的真实
  `JdbcMemoryStoreIntegrationTest` 5 项使用 `pgvector/pgvector:pg16` 实际启动，未发生
  assumption skip；`MemoryPlannerGraphTest` 验证 `[planner, coder, ops]` 闭环。
- 前端生命周期执行 Vitest `5` 个文件、`22` 个测试，全部通过；独立执行
  `npm audit --audit-level=low` 返回 `found 0 vulnerabilities`。
- 依赖扫描未发现 `langchain4j` 或 `langgraph4j`；`git diff --check` 通过；`.gitignore`
  继续排除 `target/`、`node_modules/`、`.frontend/`、日志和密钥文件。
- 首次根目录门禁受到外层 `120` 秒工具超时影响，命令已推进到前端测试但输出未返回；检查
  进程确认 Maven、Surefire 和 Node 随后自然退出、没有残留。随后用 `600` 秒工具上限重新
  执行同一 `mvn clean verify`，取得上述明确 `BUILD SUCCESS`。
- 验收后 `docker ps -a --filter label=com.agent.runtime.managed=true` 无输出，未发现本阶段
  Maven、WinPTY、Bash 或 Playwright 进程；工作区状态干净。其他项目已有 Docker 容器未纳入
  本阶段清理范围。

## Phase 6.3：OpenTelemetry、Langfuse 与 Bad Case 归因

### 6.3.1 ThreadLocal 上下文不能依赖线程继承

**【问题现象】** 模型路由在节点虚拟线程中执行，观测器若从隐式线程上下文取当前节点，跨线程调用会找不到父节点，Generation Span 可能脱离 Run 拓扑。

**【根因分析】** `ThreadLocal` 只在明确绑定的执行范围内有效，虚拟线程调度不会自动把业务上下文传播到任意异步任务。

**【解决方案/代码级实现】** `StateGraph` 在节点 callable 内绑定 `NodeExecutionContext`，`finally` 清理；`OpenTelemetryRunTracePublisher` 使用 `Context.root().with(parentSpan)` 显式建立父子关系。工作流测试验证 Run -> Node -> Generation 父子链。

### 6.3.2 Span 生命周期必须和终态、关闭语义分离

**【问题现象】** 节点失败、HITL 中断恢复和 publisher 关闭会留下活动 Span；重复终态事件还可能二次结束同一 Span。

**【根因分析】** Run、Node、Generation 是不同生命周期，恢复会产生新的 Run 段，不能用一个全局 Span 或依赖 exporter 自动结束。

**【解决方案/代码级实现】** 发布器分别维护 Run、Node、Generation 状态；完成、失败、拒绝和中断按事件精确结束，审批后重建恢复段；关闭时清理所有活动对象，重复 Generation 结束明确失败。

### 6.3.3 OTLP Header 和 Token 空值需要保留协议事实

**【问题现象】** Langfuse OTLP 接收端要求原样 endpoint、Authorization 和 ingestion 版本；模型响应缺少 usage 时，强行写入零值会伪造 Token 统计。

**【根因分析】** OTLP HTTP/protobuf 的 Header 是供应商协议边界，usage 在 OpenAI 兼容响应中是可选对象，不存在不等于消耗为零。

**【解决方案/代码级实现】** `OpenTelemetryConfiguration` 原样传递 endpoint 和 Authorization，并固定 `x-langfuse-ingestion-version: 4`；`ModelRouter` 将缺失 usage 映射为 `Optional.empty()`，有 usage 时校验总数等于输入与输出之和。

### 6.3.4 Trace 发布链失败不能破坏权威状态

**【问题现象】** 一个 Trace publisher 抛异常时，后续 publisher 不再收到事件，终态日志清理也可能被跳过。

**【根因分析】** 发布链是多个独立副作用；将其当作单一调用会把一个通道故障扩散到其他通道和 Run 状态。

**【解决方案/代码级实现】** `RunLifecycleEventPublisher` 冻结 publisher 列表，逐项发布；首异常作为主异常，后续异常加入 suppressed，终态事件始终执行日志总线清理。Checkpoint 仍由 PostgreSQL 作为 SSOT。

### 6.3.5 Bad Case 必须有 scope 和类型门禁

**【问题现象】** 失败 Run 的证据可能缺少 repository/user scope，或 extractor 混入 `USER_PREFERENCE`；如果先 embedding 再校验，会产生部分写入。

**【根因分析】** 长期记忆的隔离键和类型是持久化契约，不是可由调用方宽松推断的文本标签。

**【解决方案/代码级实现】** `RunBadCaseAttributor` 只读取固定状态键和 allowlist 证据，`ops.exitCode` 必须是十进制整数；缺少 scope 直接失败。`MemoryManager.captureBadCases` 在生成 ID、hash、embedding 和 upsert 之前校验整批均为 `MemoryType.BAD_CASE`，单字段最多 4,000 个 UTF-16 code unit、完整 source 最多 20,000。

### 6.3.6 证据记录

本阶段提交为 `ba5bc68`、`3d5aa20`、`b87cafd`、`5bdf69e`、`6f41cf0`、`3990763`、`3b998d8`、`156718c` 和 `22ed013`；对应协议、上下文、路由、Span、OTLP、发布链、类型门禁、失败归因和迁移隔离测试均已执行。

2026-08-04 在显式 JDK `21.0.2` 和 Docker Desktop Engine `27.4.0` 环境执行根目录 `mvn clean verify`，返回退出码 `0`：

- Surefire XML 汇总 `239` 项 Java 测试，失败、错误、跳过均为 `0`；真实 Docker、PTY、Chromium、PostgreSQL 与 pgvector 测试均在本轮执行。
- Vitest `5` 个文件、`22` 项测试全部通过；`npm audit --audit-level=low` 返回 `found 0 vulnerabilities`。
- 首轮根构建发现 `agent-rag` 依赖引入两个 `V1` Flyway 迁移，修复为独立 `db/rag-migration` 资源路径后，Web PostgreSQL 集成测试和第二次根构建通过。
- 禁止依赖扫描未发现 `langchain4j` 或 `langgraph4j`；`git diff --check` 通过；`docker ps -a --filter label=com.agent.runtime.managed=true` 无输出。

## Phase 6.4：Benchmark、pass^k 与 TTFT

### 6.4.1 版本化任务集必须是严格数据契约

**【问题现象】** 评测任务如果依赖宽松 JSON 映射，未知字段、空行、重复 ID 或 metadata 类型
错误会被吞掉，最终报告无法复现。测试夹具还曾用多处字符串替换构造非法 JSON，错误信息因此
落在“非法 JSON”而不是“未知字段”。

**【根因分析】** JSONL 每行是独立协议对象；字符串替换不能保证只修改目标对象，Jackson 默认
映射也不会替调用方执行字段集合和类型门禁。任务集又要求至少 50 项，任何缺失任务都应在
读取阶段失败。

**【解决方案/代码级实现】** `BenchmarkTaskSetReader` 使用 Jackson 树逐行校验精确字段集合、
文本字段、字符串 metadata、空行、非法 JSON 和重复 ID，再交给不可变 `BenchmarkTaskSet`
执行至少 50 项校验。资源文件固定为 58 条 UTF-8 JSONL，类别覆盖 `CODE`、`OPS`、`RAG`、
`TRACE`、`WEB`；未知字段测试使用明确合法 JSON 样本。

### 6.4.2 pass^k 不能静默忽略缺失重复

**【问题现象】** 只统计已返回结果会把中断、调度丢失或执行器异常误算成通过率，尤其在
`k=3` 时会掩盖某个任务只执行一次的事实。

**【根因分析】** 任务 ID 和重复序号是两个独立维度；集合大小正确也不能证明序号 `1..k`
完整，重复提交还可能覆盖原结果。

**【解决方案/代码级实现】** `BenchmarkMetrics` 对任务 ID、重复序号范围、重复唯一性和每项
`1..k` 完整性逐项校验；任一任务缺失重复立即抛出异常。`passK` 只在每个任务恰好 `k` 次
且全部 `passed` 时计入，结果最终按任务 ID和重复序号稳定排序。

### 6.4.3 TTFT 时钟边界与 Runner 并发隔离

**【问题现象】** 首 Token 缺失的失败执行被错误加入延迟分布，纳秒时间线换算还可能产生
负数或非有限值；无界提交会让配置的最大并发失效。

**【根因分析】** `firstTokenAt` 是可选事件，不等于零延迟；`startedAt`、`firstTokenAt`、
`finishedAt` 必须共享单调顺序。虚拟线程执行器本身不限制同时进入工具调用的任务数。

**【解决方案/代码级实现】** `BenchmarkTaskResult` 在构造器中拒绝越界时间线，`BenchmarkMetrics`
只把存在首 Token 的结果转换为毫秒，并以确定性的线性插值计算 p50/p95。`BenchmarkRunner`
使用 Java 21 虚拟线程配合 `Semaphore` 实施请求级最大并发，执行器异常转换为包含完整堆栈
的失败结果，关闭后拒绝新运行。

### 6.4.4 真实 Agent 工作流不能用自由文本裁判

**【问题现象】** 将任务 prompt 或模型输出文本与 success criteria 做模糊匹配，会把格式
变化当作成功，也无法证明 Run 真的到达终态或首事件确实产生。

**【根因分析】** 业务成功条件属于调用方领域，`agent-eval` 不应猜测状态键、模型文本或
不同节点的语义；`AgentRunService` 的权威终态事实是 `RunCheckpoint`，首 Token 由调用方
接入实际的 `RunLogEvent` 日志总线提供。`TraceEvent.NodeStarted` 只是节点生命周期事件，
不代表模型或终端已经产生首个可见字节。

**【解决方案/代码级实现】** `AgentRunBenchmarkExecutor` 通过构造器注入
`BenchmarkSuccessEvaluator` 和首事件时间源，初始状态使用精确声明的
`benchmark.taskId`、`benchmark.category`、`benchmark.prompt`、`benchmark.successCriteria`
变量，终态由 `AgentRunService.get` 读取。成功只由评估器结合 `RunCheckpoint` 判定，首事件
时间由 `RunLogEvent` 保留器通过构造器注入；任何超时或异常均保留完整堆栈。

### 6.4.5 审查暴露的协议与生命周期门禁

**【问题现象】** 首轮实现允许 Jackson 接受重复字段和尾随 JSON 根对象，报告调用方可以
直接构造与原始结果不一致的聚合字段，Writer 还会关闭调用方提供的输出流。Benchmark
超时只返回失败结果而不取消底层 Agent Run，导致超时后仍继续占用并发槽位；并发发布的
异步日志用非线程安全的快照读取，重复执行会出现不稳定的 `passK`。

**【根因分析】** 默认 JSON 映射器和 record 构造器只验证语法，不验证事件协议和聚合不变量；
评测超时边界与 AgentRunService 的实际生命周期没有连接。非重放日志总线也不能在发布前
假定首事件已可查询。

**【解决方案/代码级实现】** `BenchmarkTaskSetReader` 对注入映射器做副本配置，开启
`STRICT_DUPLICATE_DETECTION` 和 `FAIL_ON_TRAILING_TOKENS`。`BenchmarkReport` 校验结果数量、
任务/重复序号完备性、逐任务 `TaskMetrics`、失败堆栈与 TTFT 计数的聚合一致性；
`BenchmarkReportWriter` 禁用 `AUTO_CLOSE_TARGET`，保留调用方流所有权。`AgentRunService.cancel`
先以乐观版本追加 `FAILED` 取消快照，再中断该 Run 的全部活动 Future；`StateGraph` 在外层
等待被中断时取消当前节点 Future，异常路径读取最新权威快照，避免覆盖取消结果。
真实工作流测试发布并记录 `RunLogEvent`，成功标准显式允许业务定义的非 `RUNNING` 终态，
并用门闩确认进入节点后再验证超时取消。

**【证据】** `BenchmarkTaskSetReaderTest` 的重复字段/尾随根对象测试、
`BenchmarkMetricsTest` 的逐任务报告门禁、`BenchmarkRunnerTest` 的输出流所有权测试、
`AgentRunServiceTest.cancelsRunningRunAndInterruptsNode` 和
`AgentRunBenchmarkWorkflowTest` 的等待审批/超时场景均通过。

### 6.4.6 证据记录

Task 2、3、4、5 已分别提交为 `8a432a6`、`92f4854`、`b55f462`、`96283f4`；真实工作流
测试读取 58 条任务，覆盖 `CODE`、`OPS`、`RAG`、`TRACE`，所有 Run 到达 `COMPLETED`，
首事件时间存在且报告 `passK=1.0`。

2026-08-05 在显式 JDK `21.0.2` 和 Docker Desktop Engine `27.4.0` 环境执行根目录
`mvn clean verify`，退出码为 `0`：

- Surefire XML 汇总 `261` 项 Java 测试，失败、错误、跳过均为 `0`；真实 Docker、PTY、
  Chromium、PostgreSQL 和 pgvector 测试均在本轮执行。
- 独立前端 Vitest 为 `5` 个文件、`22` 项测试全部通过；
  `npm audit --audit-level=low` 返回 `found 0 vulnerabilities`。
- `git diff --check` 通过；精确模块路径扫描未发现 `langchain4j` 或 `langgraph4j`；
  `docker ps -a --filter label=com.agent.runtime.managed=true` 返回 0 个容器。

## Phase 2 补充：JGit index 与容器 bind 边界

**【问题现象】** 生产 Code Agent 在 Compose 工作区中成功写入文件后，宿主 Windows Git
曾显示 tracked 文件删除并重新变为 untracked；JGit 测试仓库却只显示 staged 修改。

**【根因分析】** JGit ApplyCommand 不只写工作树，还会重写 Repository.getIndexFile()。
在 Windows 工作区通过 Docker bind mount 由 Linux JVM 原子替换 index 时，宿主 Git 的 index
扩展和文件项可能被丢弃。这样即使文件内容正确，git diff 和后续修复循环也失去可靠基线。

**【解决方案/代码级实现】** AstService 在应用补丁前读取 JGit 返回的精确 index 文件字节，
让 JGit 完成 UTF-8 Unified Diff 的工作树写入和路径校验，然后在 finally 中恢复原 index；
无 index 的新仓库则删除 ApplyCommand 新建的 index。回归测试先提交真实 tracked 文件，断言
工作树出现 modified、staged 集合为空且 index 字节完全不变。该保护不自动提交，也不吞掉恢复
失败异常。

## Phase 5 补充：生产 Code Agent 运行边界

**【问题现象】** 固定 demo 图可以完成页面验收，但无法证明用户输入真的驱动 Planner、Coder、
Ops 和 Reviewer；外部模型网关还可能因为 base URL 与路径重复或模型不可用而返回 404/403。

**【根因分析】** 任务优先接口、工作区来源和模型端点是三个独立协议。把 /v1 同时写入
AGENT_LLM_BASE_URL 与 AGENT_LLM_CHAT_COMPLETIONS_PATH 会产生 /v1/v1/chat/completions；
模型列表存在也不代表该网关账号允许 Chat Completions。Compose 内的容器路径也不能直接
当作 Docker Engine 可见的宿主 bind source。

**【解决方案/代码级实现】** POST /api/runs/code-agent 只接收精确 task 优先请求，校验工作区
位于 AGENT_CODE_WORKSPACE。Compose 以 source container 名称和 /agent-workspace 精确解析
唯一、可写、非 named-volume mount；一次性沙箱只接收这一条 bind，结束后强制删除。模型配置
约定根地址不含重复 API 前缀，所有请求、响应和错误栈写入 PostgreSQL Checkpoint。

**【本轮真实证据】** 2026-08-06 使用 Docker Desktop Engine 27.4.0、Compose local、
Java 21 运行真实 Spring Web、PostgreSQL、JGit、Docker Bash、REST 和 Chromium 页面；mock
OpenAI 兼容端点仅替代不可用外部网关。浏览器输入自然语言任务后，Run 为 COMPLETED，
planner.model=mock-code、coder.updatedFiles=greeting.txt、ops.exitCode=0、ops.stdout 为
hello agent4j、reviewer.approved=true；宿主 Git 保持标准工作树 modified Diff，Monaco、xterm、
Reviewer 和 Trace 均可见。真实网关在修正重复 /v1 后仍对已列模型返回 HTTP 403 upstream_error，
因此本轮没有把外部模型调用伪装成成功。

## 本轮补充：可观测问答闭环与有界执行

## 本轮补充：会话持久化、工作区边界与 Run 投影

### 【问题现象】刷新页面或发送第二个问题后，上一次对话消失

早期工作台只持有当前 Run 的 React 状态。页面重新加载后只恢复一个 Run 快照，无法找到
用户、项目工作区和历史轮次；浏览器若把 `userId` 放在请求体中，还会让客户端伪造身份。

### 【根因分析】

Run 是一次执行，不是对话本身。把两者混用会导致“执行证据存在但聊天记忆不存在”，也无法
定义工作区权限。仅依赖 `localStorage` 又无法提供跨设备一致性、并发写入裁决和服务端审计。

### 【解决方案/代码级实现】

PostgreSQL 新增 `agent_users`、`agent_workspaces`、`agent_workspace_members`、
`agent_conversations` 和 `agent_conversation_turns`。会话以 `(user, workspace)` 为边界，
成员权限精确分为 `VIEWER`、`OPERATOR`、`OWNER`；工作区路径在真实目录和配置根目录内校验。
轮次通过 `PENDING -> RUNNING -> COMPLETED/FAILED` 状态机、唯一活动轮次和行锁保证顺序，
首轮标题由服务端生成。浏览器只传 `content`/`reviewerUrl`，身份从 `ActorResolver` 获取。

`ConversationContextProvider` 只向 Planner 注入最近 20 个已完成轮次且不超过 32,000 个 Java
字符，并始终按用户/助手成对截断。`ConversationRunProjector` 监听 Run 终态事件，把
`final_response`、`reviewer.feedback`、`reviewer.summary` 或 `planner.response` 投影回当前
轮次；重复终态事件幂等处理，Run 启动异常保留完整堆栈。前端 URL 保存会话 ID，刷新时重新读取
服务端 turns；Run 仍负责 Trace、终端、Diff 和审批证据，二者不再互相替代。

这里还暴露了一个典型的异步提交竞态：若 `AgentRunService` 创建 Checkpoint 后立即调度虚拟线程，
而应用层随后才把 `run_id` 写入轮次，极快 Run 的终态事件会先于绑定到达，投影器查询为空后事件
永久丢失。修复是在核心启动 API 中增加同步 `beforeDispatch` 边界，严格执行“创建 Run -> 绑定
轮次 -> 调度图”。系统级 `findTurnByRunId` 直接查询 `agent_conversation_turns`，不复用带成员联结
的授权查询，避免多人工作区把唯一轮次放大为多行。归档再次通过 `WorkspaceAccessService` 要求
`OPERATOR`，前端则以当前会话 turns 中的精确 `runId` 隔离审批和执行证据，并主动关闭旧连接。
若进程仍在终态事件发布窗口退出，读取轮次或提交下一轮前会对 `RUNNING` Turn 的 `run_id` 查询
PostgreSQL Checkpoint；发现 `COMPLETED`、`FAILED` 或 `REJECTED` 后复用同一幂等投影逻辑补写
轮次终态，避免活动轮次冲突永久阻断对话。

### 【证据】

`AgentRunServiceTest` 覆盖绑定先于虚拟线程调度；`JdbcConversationRepositoryIntegrationTest`
覆盖成员隔离、多人工作区系统投影、禁用用户、两轮顺序、并发活动轮次、归档冲突和 Unicode
标题截断；`ConversationControllerTest` 覆盖严格 JSON 字段与错误映射；
`conversationApi.test.ts`、`useConversationWorkspace.test.tsx` 和 `Workbench.test.tsx` 覆盖
浏览器身份、工作区切换、迟到响应丢弃、刷新恢复、历史轮次提交、Run 跟随连接和跨会话证据隔离。

### 【问题现象】简单问题误入代码链

用户只询问模型身份或架构说明时，流程仍然进入 Coder/Ops，最终因为缺少
`ops.command` 或工作区快照超限失败；即使模型已经生成回答，前端也只显示“已完成”和执行占位。

### 【根因分析】

Planner 过去把所有输入都当作代码任务，缺少“先识别意图、再选择能力”的路由层。代码修改、
工具执行与普通问答的上下文预算和成功标准不同，不能共享同一条强制执行链。与此同时，前端只关注
节点终态，没有消费 `final_response`，导致状态机中已有的业务结果丢失在 UI 边界。

### 【解决方案/代码级实现】

`PlannerNode` 先用动作词优先规则识别代码意图；高置信聊天直接调用
`TaskType.QUICK_CLASSIFICATION` 并写入精确状态键 `final_response`，设置
`planner.route=chat` 后路由到 `END`。未命中快路径时只执行一次语义路由，模型输出严格限制为
`chat` 或 `agent`；只有 `agent` 才进入 Planner/Coder/Ops/Reviewer。聊天路径不要求
`planner.repositoryId`、`planner.userId`，也不召回代码记忆。异常保留完整堆栈并设置
`planner.route=failed`，避免静默失败。

`WorkspaceSnapshotService.capture` 继续作为严格门禁，适用于需要完整、可审计快照的场景；
`captureForPrompt` 则在文件数和字节预算内生成稳定的部分视图，超出预算时跳过文件并记录摘要，
避免大型工作区让一次普通代码请求直接失败。`CoderNode` 明确告知模型该上下文是受预算限制的部分视图，
防止模型把截断内容误当作完整仓库。

### 【问题现象】执行期间前端长时间停留在“运行中”

节点开始和结束事件存在，但节点内部的模型请求、补丁处理和终端执行没有中间反馈，SSE/WebSocket
订阅端无法解释延迟来源。

### 【根因分析】

原有事件协议只描述生命周期终态；新增事件如果只在 Java 端定义而没有同步经过 OTel、SSE 和
TypeScript 解码器，就会在跨模块边界被丢弃或降级为未知事件。

### 【解决方案/代码级实现】

`GraphExecutionListener.onNodeProgress` 在节点执行中发布 `TraceEvent.NodeProgress`，并使用
`TraceEventType.NODE_PROGRESS` 贯穿 `StateGraph`、`AgentRunService`、
`OpenTelemetryRunTracePublisher`、`RunTraceController` 的 SSE 流以及前端 `runApi` 精确解码器。
OTel 将摘要写入 `agent.node.progress` 事件和 `agent.progress.summary` 属性，前端
`TraceTimeline` 展示节点名与可读摘要，保留原始 traceId/runId 供审计关联。

### 【问题现象】聊天 Run 完成但页面没有最终答案

流程状态已经包含 `final_response`，页面却仍展示代码执行阶段列表，用户无法区分“回答已生成”和“代码已修改”。

### 【解决方案/代码级实现】

`AgentConversation` 读取 `planner.route` 与 `final_response`：聊天路由直接渲染最终回答并隐藏代码阶段；
代码路由继续展示 Planner、Coder、Ops、Reviewer 和实时进度。`RunTraceController` 订阅 trace 后先读取
权威 Checkpoint，再发送状态快照，处理“事件先到、快照后写”的竞态，保证新订阅者既能看到最终答案，也能
继续接收后续事件。

### 【证据】

`PlannerNodeTest` 覆盖高置信聊天、动作词优先、语义路由和异常堆栈；
`WorkspaceSnapshotServiceTest` 覆盖严格快照与有界提示快照；
`OpenTelemetryRunTracePublisherTest`、`RunTraceControllerTest` 覆盖进度事件的 OTel/SSE 协议；
`runApi.test.ts` 与 `Workbench.test.tsx` 覆盖 `NODE_PROGRESS` 解码、聊天 `final_response` 渲染和代码路径回归。

### 【问题现象】路由模型返回解释文本后连续对话直接失败，容器日志无法在宿主机审计

真实 EDD 复现了路由模型返回 `chat，因为这是自然语言问题`、Markdown 代码围栏或 JSON 包装时，
旧实现因全字符串等值比较抛出 `任务路由模型必须精确返回 chat 或 agent`。同时 Logback 虽然已经
配置了滚动文件，Compose 没有把容器内 `logs` 目录绑定到宿主机，重建容器后日志不便检索。

### 【根因分析】

路由协议要求模型表达一个离散值，但模型输出仍是自然语言通道，格式包装和解释文本是可预期的协议
偏差；把格式偏差当作网络故障会中断整个问答链。日志文件路径只在应用容器内部存在，部署编排没有
定义日志目录的宿主机生命周期，控制台输出也无法替代按 Run/Trace/Node/Model 关联的归档审计。

### 【解决方案/代码级实现】

`PlannerNode` 先严格识别精确值，再解析完整 Markdown 围栏、JSON `route` 字段和以单一路由开头的
标点解释文本；无法证明路由且请求不含代码动作词时安全回退 `chat`，并以 WARN 记录安全截断摘要。
即使模型明确返回 `agent`，若当前自然语言任务没有任何明确工具/代码动作词，也会再次降级为 `chat`，
避免连续对话历史把天气规划误带入代码链。真实异常仍写入 `planner.error` 和完整堆栈，网络/HTTP 故障不会被回退掩盖。Planner 的失败日志同时
带 `runId`、`traceId`、`nodeName`、`modelName` MDC（由节点上下文和 LlmClient 注入）。

Logback 使用 `agent.logging.directory`（环境变量 `AGENT_LOG_DIR`）同时写控制台和
`agent4j-current.log`，按 `agent4j-%d{yyyy-MM-dd}.log` 归档并保留 30 天；两个 Compose 文件将
`${AGENT_LOG_HOST_DIR:-./logs}` 挂载至 `/app/logs`，宿主机可以直接留存审计文件，`.gitignore`
继续排除 `logs/` 和 `*.log`。

EDD 使用真实 OpenAI 兼容端点验证四条预设对话，记录路由、终态、总耗时、响应首字延迟（可用时）、
错误栈和轨迹门禁，报告写入 `target/edd`。本轮实际结果为 `chat/chat/chat/agent` 全部通过，证明
“你是什么模型 -> 无车出游 -> 按天气追问”不会再因路由格式偏差中断。

### 【证据】

`PlannerNodeTest` 的路由格式回归用例、`LlmEddTest`（显式 `AGENT_LLM_ENABLED=true`）、
`docs/superpowers/specs/2026-08-07-edd-observability-design.md`、真实 EDD JSON 报告以及两份
Compose 的 `config --quiet` 校验。

## 第二篇 2A 补充：Prompt、Context 与 Intent 边界

### 【问题现象】Prompt 散落在节点代码中，无法审计版本和变量来源

Planner 的系统提示、路由提示和回答提示曾直接写在方法体内。提示词一旦调整，无法回答
“本次 Run 使用了哪一版指令”，也无法复现同一输入的模型行为。

### 【根因分析】

Prompt 被当作普通字符串，而不是 Agent 运行协议的一部分；静态指令、动态变量和模型输入
没有明确边界，节点状态也没有记录指纹。

### 【解决方案/代码级实现】

`PromptTemplate`、`RenderedPrompt` 与 `PromptCatalog` 将 Prompt 按精确名称和版本注册，
静态内容与动态变量分区渲染，并以 SHA-256 生成稳定指纹。缺失变量、重复注册和未知版本
都立即失败；Planner 将路由和回答 Prompt 的名称、版本及指纹写入状态，审计可沿
`runId/traceId` 重放确切输入。

### 【问题现象】用字符数限制上下文，模型仍可能因 token 超预算失败

不同语言、代码和 JSON 的 token 密度不同，固定字符上限不能代表模型上下文窗口；简单截断
还可能丢掉系统约束、当前问题或最新工具错误。

### 【根因分析】

上下文预算被错误地当成字符串长度问题，且没有为摘要和关键消息保留硬预算。

### 【解决方案/代码级实现】

`ContextWindowManager` 通过 `TokenEstimator` 计算估算 token，按系统消息、当前用户消息、
最新工具错误和最近历史的固定优先级构造窗口，并为摘要预留预算。超限时返回明确的
`ContextWindow` 元数据（估算 token、丢弃消息数、是否摘要），使 Planner 能把上下文决策
写入状态和 Trace，而不是静默丢消息。

### 【问题现象】自由文本路由把连续追问误送入代码链

用户先问“你是什么模型”，再追问“按天气规划”，旧 Planner 可能要求模型精确输出
`chat`/`agent`，一旦模型附带解释就抛异常；或者把没有代码动作的自然语言误判为 Agent，
随后 Coder/Ops 因缺少工作区或命令失败。

### 【根因分析】

路由协议是离散枚举，却直接消费不可信自然语言；同时路由没有把任务类型、复杂度和所需
能力作为结构化决策，连续对话上下文也没有安全的聊天快路径。

### 【解决方案/代码级实现】

`TaskRoute`、`TaskKind`、`TaskComplexity`、`RequiredCapability` 和 `TaskDecision` 组成
强类型意图协议。明确动作词优先进入 Agent；直接问答走无副作用 Chat 快路径；语义路由只
接受五字段 JSON，格式错误或无代码动作的 `agent` 结果安全回退 Chat。Chat 直接写入
`final_response` 并结束图，避免无关的 Coder/Ops 执行。

### 【问题现象】旧动作词遗漏导致真实代码任务没有进入 Coder

生产集成中“把 value.txt 改成 after 并验证”被当成聊天，因为新增动作词集合遗漏了中文
动作词“改”；任务没有修改文件，失败原因直到端到端测试才暴露。

### 【根因分析】

快路由词表属于兼容性协议，重构时只按新示例补词，未对照旧 Planner 的精确动作标记和真实
任务集建立回归矩阵。

### 【解决方案/代码级实现】

保留旧动作标记并增加回归测试，动作词识别结果在 Planner 测试和真实
Planner -> Coder -> Ops -> Reviewer 集成测试中验证。代码任务必须实际产生
`coder.updatedFiles`，不能仅凭路由字段判定成功。

### 【证据】

`PromptCatalogTest`（版本、变量和指纹）、`ContextWindowManagerTest`（预算与保留策略）、
`ModelIntentClassifierTest`（严格协议与安全回退）、`PlannerNodeTest`（聊天快路径、旧动作词
和错误堆栈）、生产图集成测试（真实文件修改）及本轮模块全量测试共同构成 2A 的验证证据。

## 第二篇 2B 补充：Memory 生命周期、Runtime 预算与 Harness

### 【问题现象】长期记忆只按检索相关性排序，旧偏好会长期压制架构规则

向量和 BM25 分数只能回答“文本是否相关”，不能回答“这条记忆现在是否仍应生效”。如果用户
偏好和历史 Bad Case 永不衰减，数月前的临时选择会持续进入 Planner；反过来，如果统一衰减，
项目架构规则也会随时间失效。

### 【根因分析】

记忆条目缺少重要度、访问频率和最近访问时间，召回层也没有区分动态记忆与长期规则。数据库
若只更新内容时间，无法审计“被频繁使用但内容未变化”的记忆。

### 【解决方案/代码级实现】

`MemoryEntry`、`MemoryDraft` 和 PostgreSQL V3 增加 `importance`、`accessCount`、
`lastAccessedAt`。用户偏好使用 30 天半衰期，Bad Case 使用 14 天半衰期，
`ARCHITECTURE_RULE` 不衰减；最终排序固定为
`0.8 * retrievalScore + 0.2 * lifecycleScore`。命中后按 repository/user/type/UUID 精确
范围更新访问次数。访问写回失败只进入 `MemoryAuditSink`，不会丢弃已经完成的召回结果；
审计端口再次失败时作为 suppressed 保留。

### 【问题现象】最大步数无法治理节点内部卡死和“有 Trace、无业务进展”循环

旧图只在节点之间检查 `maxSteps`。节点内部 HTTP、浏览器或工具等待卡死时永远到不了下一次
检查；节点每次只追加 trace 时，表面有事件流，实际 `messages/variables` 没有任何变化。

### 【根因分析】

虚拟线程降低阻塞成本，但不提供运行时预算和业务进展判定。把“发布过事件”当成业务进展又会
让空转循环绕过门禁；把 token 数混入 progress 时钟则会掩盖模型长时间无响应。

### 【解决方案/代码级实现】

`ExecutionBudget` 同时限制总时长、空闲时长、Token、步数和无进展次数。`StateGraph` 在
`Future.get` 的有界等待循环中按 `MAX_DURATION -> IDLE_TIMEOUT -> TOKEN_BUDGET ->
MAX_STEPS -> NO_PROGRESS` 固定顺序检查；超时会取消节点 Future。过程摘要只刷新空闲时钟，
模型响应的 `usage.totalTokens` 由 `ModelRouter` 交给 `NodeExecutionContext.consumeTokens`；
无进展只比较不可变状态的 `messages` 和 `variables`，明确忽略自然增长的 trace。

预算耗尽写入 `runtime.stopReason`、`runtime.observed`、`runtime.limit` 和
`runtime.consumedTokens`，随后进入 FAILED Checkpoint 与既有 `TraceEvent.Failed`，恢复和
Web 前端都读取同一个 PostgreSQL 权威状态。

### 【问题现象】横切治理直接写进节点，观测故障会改变 Agent 决策

若权限、审计、工具计量和日志逻辑散落在 Planner/Ops/Reviewer 内，执行顺序无法复用；如果
一个日志 Hook 抛异常就终止业务，观测系统会成为新的单点故障。反过来，把权限 Hook 也吞掉，
危险动作又会越过门禁。

### 【根因分析】

横切能力缺少统一的节点/工具生命周期协议，也没有区分“可隔离的观测失败”和“必须拒绝的治理
失败”。工具异常被多层 Future 包装后，若 Hook 替换原异常，修复循环会失去根因。

### 【解决方案/代码级实现】

`HarnessHookChain` 按注册顺序发布 `BEFORE_NODE/AFTER_NODE/BEFORE_TOOL/AFTER_TOOL/
FAILURE/BUDGET_EXHAUSTED`。非关键 Hook 失败进入 `HarnessAuditSink` 后继续，关键 Hook 失败
立即传播并保留 cause。`NodeExecutionContext.callTool` 对终端和浏览器证据建立统一边界；
工具失败先发布 FAILURE，再原样抛出，Hook 失败作为 suppressed 附着。生产 Hook 事件通过
SLF4J 进入已有按日滚动日志，仍由 Checkpoint 而不是日志裁决业务状态。

### 【证据】

`MemoryLifecycleTest`、`MemoryLifecycleManagerTest`、真实 PostgreSQL
`JdbcMemoryLifecycleIntegrationTest`、`StateGraphBudgetTest`、
`AgentRunServiceBudgetTest`、`HarnessHookChainTest`、`StateGraphHarnessTest`、Ops/Reviewer
工具边界测试、`RuntimeHarnessEddTest` 和显式开关的 `LlmEddTest` 共同覆盖 2B。

## 第三篇 3A 补充：自适应 RAG 流水线与 EDD

### 【问题现象】多查询召回直接相加会放大结果数量和分数

查询改写后得到多组有序召回。如果把每组的原始分数直接相加，分数尺度和列表长度会
影响最终顺序，重复命中也无法说明“在几组结果中稳定出现”。

### 【根因分析】

向量、BM25 和符号分数不是跨查询可加的统一概率；列表排名才是每个查询都具备的稳定
信息。融合还必须按精确 `childId` 合并，并验证同一标识对应的父子正文一致。

### 【解决方案/代码级实现】

`ReciprocalRankFusion` 固定使用 `1 / (60 + rank)`，每组 rank 从 1 开始，对重复
`childId` 累加倒数排名分；冲突正文立即失败，输出按分数、路径、ordinal、childId
稳定排序。`RagRetrievalPipeline` 保留原始查询为第一组，改写查询只能追加，基础召回
失败不降级且保留原始 cause。

### 【问题现象】HyDE 生成文本污染 BM25 结果

HyDE 的假设正文通常比用户问题更长。若把它同时作为词法检索文本，召回会偏向模型生成
的术语，而不是用户实际输入的文件、类名和错误信息。

### 【根因分析】

HyDE 的用途是改善向量空间中的语义邻近度，不是替换用户的词法证据。两种检索通道需要
明确区分输入来源。

### 【解决方案/代码级实现】

流水线只用 HyDE 正文生成原始查询的 embedding；`RagQuery.query` 始终保留原始查询，
改写查询不携带 HyDE 向量。阶段证据明确记录“HyDE 仅替换原始查询的向量”，可从报告
复核该边界。

### 【问题现象】外部 reranker 返回未知、重复或超限标识

模型或远程精排服务可能返回不属于本批召回的 `childId`、重复标识、空项或超过 limit
的结果。直接按返回顺序注入上下文会导致证据错配或绕过预算。

### 【根因分析】

rerank 是不可信边界。它只应返回已召回子块的排序和非负有限分数，不能重新定义文档
身份，也不能改变上下文数量协议。

### 【解决方案/代码级实现】

`RerankValidation` 先建立精确 `childId` 集合，再检查 null、未知标识、重复标识、分数
有限性和 limit。校验失败时流水线保存完整堆栈并退回 RRF 顺序，`RERANK` 证据标记为
`DEGRADED`，已经可靠的基础召回仍可继续。

### 【问题现象】按字符截断 token 上下文破坏代码证据

为了满足上下文窗口，直接截断父块字符串会把方法、括号或错误堆栈截成不可编译、不可
解释的片段，并且字符数不等于 token 数。

### 【根因分析】

父块和子块是 AST/语义边界，必须以完整文档作为原子单位；预算选择还要处理同一父块的
多条子命中，避免重复注入。

### 【解决方案/代码级实现】

`RagTokenBudgetSelector` 按精排顺序先尝试完整父块；父块放不下时尝试完整子块；同一
`parentId` 只注入一次；后续放不下的文档完整跳过，第一条子块也超预算则抛出包含
`estimatedTokens` 与 `limit` 的 `RagContextBudgetExceededException`。结果的 token 总数
再次由 `RagRetrievalResult` 校验。

### 【问题现象】增强阶段异常被吞掉，无法判断结果是否可信

查询改写、HyDE 或 rerank 的异常如果只打印一句“已降级”，后续维护者无法知道原始端点、
模型和调用栈，也无法区分增强失败与基础检索失败。

### 【根因分析】

增强是可选能力，失败时可以回退；基础 embedding、召回和融合是证据来源，失败时不能
伪造空结果。两类错误需要不同的终止语义和审计字段。

### 【解决方案/代码级实现】

`RagStageEvidence` 固定记录阶段、输入/输出计数、详情和完整 `errorStack`。改写、HyDE、
rerank 失败写入 `DEGRADED` 并保留最后一个可证明结果；embedding、基础召回和融合失败
包装为 `RagPipelineException` 并保留 cause。确定性 `RagPipelineEddTest` 覆盖模糊改写、
HyDE、重复 RRF、rerank、父转子预算以及三种增强失败，报告固定写入
`agent-eval/target/edd/rag-pipeline-edd.json`，每个任务均包含六阶段证据。

### 【证据】

`RagRetrievalPipelineTest`、`RagTokenBudgetSelectorTest`、`ReciprocalRankFusionTest`、
`LexicalCoverageRerankerTest`、`ModelRetrievalEnhancerTest` 和 `RagPipelineEddTest`。
真实模型协议 EDD 仅在 `AGENT_LLM_ENABLED=true` 时运行；关闭时由 JUnit assumption
明确跳过，不让普通构建依赖外部端点。

## 第三篇 3B 补充：项目知识编译与安全边界

### 【问题现象】Windows 会把错误大小写知识文件当成正确路径

在 Windows 上直接执行 `root.resolve("AGENTS.md")` 再判断 `exists`，可能命中实际名为
`agents.md` 的目录项。这样会把开发者没有声明为规则的普通 Markdown 注入系统 Prompt，
而 Linux 部署又不会加载，形成跨环境行为漂移。

### 【根因分析】

`Path.resolve` 只拼接路径，最终匹配语义由文件系统决定。大小写不敏感文件系统不能替业务
协议保证文件名精确相等。

### 【解决方案/代码级实现】

`ProjectKnowledgeCompiler` 对根到 activePath 的每一级目录调用 `Files.list`，读取实际
目录项名称，再用 `String.equals` 精确匹配 `SOUL.md`、`AGENTS.md`、`CLAUDE.md`。加载
顺序固定为根 `SOUL.md`、根到活动目录的全部 `AGENTS.md`、再到全部 `CLAUDE.md`，不扫描
用户 Home，也不做任何大小写或格式推断。

### 【问题现象】只看 mtime 的热重载会继续使用旧规则

文件同步工具、Git 操作或测试可以在内容变化后恢复原 mtime。若缓存键只包含路径和修改
时间，Agent 会继续注入旧规则，且日志看起来仍是缓存正常命中。

### 【根因分析】

mtime 是文件系统元数据，不是正文身份；时间精度和写入恢复操作都可能让不同内容具有相同
时间戳。

### 【解决方案/代码级实现】

每次加载都严格读取 UTF-8 正文并计算文件 SHA-256；来源清单指纹按固定顺序组合
`fileType + relativePath + sourceSha256`。缓存键包含真实 root、真实 activePath 和
`maxTokens`，命中时还必须比较重新扫描得到的完整来源指纹。内容不变返回同一不可变对象；
内容改变即使 mtime 恢复也产生新上下文和新指纹。

### 【问题现象】工作区内的知识文件名可以通过符号链接读取工作区外内容

只检查链接路径位于仓库内并不安全。攻击者可以把 `AGENTS.md` 指向仓库外的密钥、用户级
指令或其他项目文件，绕过普通的 `startsWith(workspaceRoot)` 检查。

### 【根因分析】

逻辑路径边界和真实目标边界不同。`normalize` 只消除 `.`/`..`，不会解析符号链接。

### 【解决方案/代码级实现】

workspaceRoot 与 activePath 首先执行 `toRealPath`；每个知识文件在读取前也解析真实目标并
再次验证仍以真实 root 开头。路径解析、读取和 UTF-8 解码异常保留 cause；文件目标越界
立即终止，不回退到不可信正文。Windows 无符号链接权限时，仅对应集成单例通过 JUnit
assumption 明确跳过。

### 【问题现象】字符截断会把规则变成另一条规则

按剩余字符数截断 Markdown 可能删除否定词、代码围栏结尾或安全约束后半段。把可选
`SOUL.md` 先塞满预算，还会挤掉根 `AGENTS.md`，导致项目最明确的工程规则反而缺失。

### 【根因分析】

规则文件是完整语义单元，不是可任意截断的搜索片段；预算优先级也不能简单等同展示顺序。

### 【解决方案/代码级实现】

单文件先执行 25,000 bytes、200 行和严格 UTF-8 门禁。token 选择只在完整来源边界发生，
根 `AGENTS.md` 存在时先预留预算，单独超限则抛出带 `observed/limit` 的 `TOKENS` 异常；
其他来源按固定顺序完整加入或完整跳过，最终 Prompt 仍按固定展示顺序渲染并重新估算。
代码 RAG 证据同样只按完整文档反向移除，不裁断正文。

### 【问题现象】基础 RAG 失败被伪装成“没有相关代码”

数据库、embedding 或基础召回故障若直接返回空文档，Planner 无法区分“确实没有证据”和
“证据系统不可用”，会在缺失事实的情况下继续修改代码。

### 【根因分析】

查询改写、HyDE、rerank 属于可降级增强；基础召回是代码证据来源。两者不能共享同一个
吞异常策略。

### 【解决方案/代码级实现】

`RagKnowledgeContextProvider` 先保留项目文件规则，再用剩余预算调用 RAG。增强失败映射为
对应阶段的 `DEGRADED` 证据并保留完整堆栈；基础失败在 `strict=true` 时原样终止并保留
cause，在 `strict=false` 时只返回文件规则，并新增 source 精确为 `RAG_PIPELINE` 的降级
证据。最终上下文使用固定双标题、内容指纹和不可变证据集合。

### 【证据】

`ProjectKnowledgeCompilerTest`、`RagKnowledgeContextProviderTest` 和
`ProjectKnowledgeEddTest`。确定性 EDD 报告固定写入
`agent-eval/target/edd/project-knowledge-edd.json`，六个场景均包含
`taskId/passed/sourceCount/fingerprint/estimatedTokens/degraded/evidence`。

## 第三篇 3C 补充：按需索引一致性与 single-flight

### 【问题现象】指纹扫描与切片分别读取磁盘会产生混合版本索引

如果先扫描文件计算 workspace fingerprint，随后切片器再次打开文件，两个步骤之间发生的
代码修改会让数据库保存“旧指纹 + 新正文”。后续请求看到指纹不一致会重复索引；更严重时，
审计记录无法证明当前向量到底来自哪一版源码。

### 【根因分析】

指纹和切片虽然属于同一次索引事务，但旧接口分别消费路径，磁盘读取不是事务快照。数据库
事务只能保证父块、子块和元数据一起提交，不能回滚事务外已经变化的文件系统。

### 【解决方案/代码级实现】

`RepositorySourceScanner` 一次捕获规范化路径、严格 UTF-8 正文、单文件 SHA-256 和仓库
SHA-256；`CodebaseChunker` 与 `CodebaseIngestionService` 只消费该不可变
`RepositorySnapshot`。`RagRepositoryIndex` 使用同一快照指纹和实际父子数量，并与父子块在
`JdbcRagStore` 的同一 PostgreSQL 事务中替换。快照后修改磁盘不会污染本轮切片正文。

### 【问题现象】并发首次查询会重复扫描和调用 embedding

同一 repositoryId 在索引缺失或代码变化时可能同时收到多个知识查询。每个请求独立 ingest
会重复读取整个项目、重复消耗 embedding 配额，并竞争替换同一组数据库行。

### 【根因分析】

数据库指纹只能跳过已经提交的索引，不能合并正在执行的索引。单纯先查 Map 再放 Future
仍是 check-then-act 竞态；Future 失败后若留在 Map 中，后续请求还会永久复用失败结果。

### 【解决方案/代码级实现】

`CodebaseIndexCoordinator` 以 repositoryId 为精确键，通过 `ConcurrentHashMap.compute`
原子创建或复用未完成的 `CompletableFuture<RagRepositoryIndex>`。扫描、切片和 embedding
全部提交到 `Executors.newVirtualThreadPerTaskExecutor()`；数据库指纹一致时直接返回已提交
元数据。完成或失败后使用键和值双重匹配删除 Future，避免旧任务删除新重试；已完成旧值即使
尚在 Map 中也不会阻止下一轮索引。`IndexingKnowledgeContextProvider` 在调用线程按配置
`Duration` 等待，超时、中断和执行异常分别保留原始 cause，中断路径恢复线程中断标记。

### 【证据】

`CodebaseIndexCoordinatorTest` 验证同仓库并发只调用一次 embedding、虚拟线程执行、指纹命中
跳过、失败后重试和完成回调内再次索引；`IndexingKnowledgeContextProviderTest` 验证等待顺序、
超时、执行失败与中断传播。Task 6 指定测试连续三轮通过；随后 `agent-sandbox` 43、
`agent-core` 168、`agent-rag` 115 个测试全量通过，0 failures、0 errors。2 个 skip 均为
Windows 无符号链接权限的明确 assumption，真实 pgvector 集成测试未跳过。

### 【问题现象】第二个 Flyway Bean 会让 Web 主迁移自动配置退场

RAG 的迁移文件同样从 V1 开始。如果把 `db/migration` 与 `db/rag-migration` 合并到一个历史表，
Flyway 会遇到重复版本；如果直接注册另一个 `Flyway` Bean，Spring Boot 的默认 Flyway 又会因
`@ConditionalOnMissingBean` 不再创建，Web 的 Run、Checkpoint 和 Conversation 表失去迁移。

### 【根因分析】

Web schema 与 RAG schema 是两个独立版本空间，但共享同一 DataSource。Flyway 的 migration
location、history table 和 Spring Boot Bean 条件必须同时隔离，不能只改 SQL 路径。

### 【解决方案/代码级实现】

Boot 默认 Flyway 继续使用 `classpath:db/migration` 与 `flyway_schema_history`。RAG 不注册第二个
`Flyway` 类型 Bean，而是注册独立 `FlywayMigrationInitializer`；其内部 Flyway 精确使用
`classpath:db/rag-migration` 和 `flyway_rag_schema_history`。`JdbcRagStore` 明确依赖该
initializer，避免应用接流量时表结构尚未完成。

### 【问题现象】Embedding 日志可能泄漏源码或密钥，维度漂移会污染索引

Embedding 请求携带真实源码。如果直接记录请求体或带 Authorization 的请求对象，滚动日志会
持久化代码和 API Key；端点忽略 `dimensions` 或返回多个向量时，错误数据还会在写库阶段才暴露。

### 【解决方案/代码级实现】

`OpenAiEmbeddingModel` 请求固定发送单项 `input`、精确模型名与 `dimensions=8`，响应必须只有
一个 `index=0` 项、八个有限数且没有尾随 JSON。日志只记录完整 URL、model、inputCount、
HTTP 状态和 durationMs，不记录 input 正文或 Bearer。配置沿用 `AGENT_LLM_BASE_URL` 与
`AGENT_LLM_API_KEY`，RAG 只新增路径、模型和策略开关，避免重复密钥来源。

## 第三篇 3C Task 9 补充：生产闭环回归与 EDD

### 【问题现象】合法 UTF-8 的空文本文件进入 RAG 后在检索阶段才失败

生产工作区通常包含空的占位文件（例如 Windows 工具目录中的 `bash.exe`）。扫描器把它们
识别为合法 UTF-8 来源，切片器随后创建空父块和空子块；Planner 直到 token 预算选择阶段
构造 `RagContextDocument` 才抛出 `content 不能为空`，知识问答被错误标记为 Planner 失败。

### 【根因分析】

“可解码”不等于“可检索”。空白正文没有任何词法或向量证据，却绕过了扫描层的二进制和
UTF-8 门禁。错误发生在索引提交之后，导致失败请求还可能留下已经写入的空块。

### 【解决方案/代码级实现】

`CodebaseChunker.readAndChunk` 在 Java/文本分派前使用精确的 `String.isBlank()` 门禁，空白
来源不生成父块或子块；非空 Java 类仍按 JavaParser 符号边界切分。回归测试同时保留空文件、
有效文本、二进制和非法 UTF-8 文件，确认空文档不会进入存储或 `RagTokenBudgetSelector`。

### 【问题现象】PostgreSQL `timestamptz` 往返后索引对象比较失败

虚拟线程索引任务使用 `Clock.systemUTC()` 产生纳秒级 `Instant`，PostgreSQL `timestamptz`
只保存微秒级精度。首次索引返回的对象可能是 `...539381600Z`，数据库回读是
`...539382Z`；指纹相同却因为 record 全值比较被误判为不同索引。

### 【根因分析】

数据库列精度是持久化协议的一部分。只在 JDBC 写入时截断会让内存返回值和回读值仍使用两套
语义；只在测试中放宽比较又会掩盖 single-flight 与旧索引保留逻辑的错误。

### 【解决方案/代码级实现】

`RagRepositoryIndex` 的紧凑构造器在 null 校验后统一执行
`indexedAt.truncatedTo(ChronoUnit.MICROS)`，因此所有创建路径、JDBC 写入和 JDBC 回读共享同一
规范值。真实 `pgvector/pgvector:pg16` 测试用带纳秒输入的回归断言，并验证失败 refresh 后旧
指纹和旧代码块保持不变。

### 【问题现象】只读项目问答被错误送入 Coder/Ops，或 EDD 只验证路线不验证证据

项目架构问题需要读取代码但不应写文件、执行命令或启动 Reviewer。仅断言 `route=knowledge`
无法发现 Planner 没有写入 `final_response`、RAG 阶段降级未记录、代码任务没有同时注入记忆
和项目规则等问题。

### 【根因分析】

Agent 的“通过”是用户可见回答、状态证据和副作用边界的联合协议，不是单一字符串。真实
模型与 RAG 还存在增强失败、基础失败、已持久化索引命中等不同路径，单元测试无法覆盖这些
组合时序。

### 【解决方案/代码级实现】

生产图把 `knowledge` 和 `chat` 精确映射到 `END`；`PlannerNode` 写入 `final_response`、
知识指纹、来源数量、不可变证据 JSON 和降级标志。代码路线在 Coder 前先保存长期记忆与
项目知识。`ProjectKnowledgeRouteEddTest` 使用 Mock LLM 协议响应和真实核心/RAG 对象，固定
生成 `agent-eval/target/edd/project-knowledge-route-edd.json`，六个场景每项只包含：
`taskId/route/sourceCount/fingerprint/ragStages/degraded/ttftMs/finalResponse/passed`。
场景覆盖普通 Chat、项目问答、代码任务、持久化索引跳过、增强降级和基础失败回退；Chat 与
Knowledge 路线必须有 `FINAL_RESPONSE_KEY`，Code 路线明确使用 Planner 的 `PLAN_KEY` 作为
本评测阶段的用户可见计划，不做隐式键回退；所有场景均不能出现 Planner 错误状态。`ttftMs`
由 Mock 响应创建回调记录的首个响应时刻计算，避免把完整 Planner 结束耗时冒充首字延迟。

### 【证据】

`ProductionKnowledgeIntegrationTest` 使用真实 Docker PostgreSQL，覆盖 Planner-only 项目
问答、并发首次索引、失败 refresh 旧索引保留、memory+knowledge 到 Coder、非严格回退和严格
终止；`ProjectKnowledgeRouteEddTest` 六场景全部通过并写入固定 JSON 报告。空白文本和微秒
精度回归分别由 `CodebaseChunkerTest` 与 `JdbcRagStoreIntegrationTest` 锁定。

## 第四篇 4A：统一 Tool Registry 与 Harness 执行治理

### 【问题现象】Schema 未知关键字被静默放行，工具参数在不同调用方产生不同语义

模型输出的 JSON Schema 可能包含 `$ref`、脚本表达式或当前运行时未实现的约束。若校验器
忽略未知关键字，注册阶段看似成功，真正执行时却会在不同工具适配器中出现不一致的参数边界。

### 【根因分析】

工具协议没有定义可验证的 Schema 子集，调用方把“能解析 JSON”误当成“理解 Schema”。同时，
字符串长度、整数约束和浮点有限性若交给 Jackson 默认转换，容易发生 UTF-16/code point、
小数截断以及 NaN/Infinity 的语义漂移。

### 【解决方案/代码级实现】

`JacksonToolSchemaValidator` 在注册时递归校验精确白名单：`object/string/integer/number/boolean/array`、
`properties/required/additionalProperties/items/enum/minLength/maxLength/minimum/maximum/title/description`。
任何未支持关键字都以精确 JSON Pointer 抛出 `ToolSchemaException`；字符串长度按 Unicode code point，
数值使用 `BigDecimal`，整数约束拒绝小数和非有限值。参数校验在授权前执行，未知字段只在
`additionalProperties=false` 时拒绝。这样 Schema 失败不会进入 handler，也不会依赖调用方猜测格式。

### 【问题现象】审批结果被调用方当作允许执行，或参数正文进入审计日志

高风险工具如果把“需要审批”转换成布尔值，调用方很容易在等待逻辑中误执行；直接记录 arguments
又会把源码、Bearer 或业务密钥写入长期审计文件。

### 【根因分析】

权限、风险与审计没有统一不可变协议，工具名称和参数文本还被错误地用于推断权限。缺少能力、
拒绝、待审批和允许四种状态没有清晰区分，导致 UI 状态与实际 handler 调用不一致。

### 【解决方案/代码级实现】

`DefaultToolAuthorizer` 只计算 `requiredCapabilities - grantedCapabilities`，再按 `ToolRiskLevel.HIGH`
和 `approvalGranted` 返回精确的 `DENIED/APPROVAL_REQUIRED/ALLOWED`；不读取工具名或参数正文。
`ToolAuditEvent` 只保存参数规范化 JSON 的小写 SHA-256、风险、状态、耗时、异常类型和取消标志。
未知工具的 `FAILED/ToolNotFoundException` 才允许没有风险级别，其余事件必须带已注册定义的风险级别，
从协议上阻止“伪造已授权工具”的审计记录。

### 【问题现象】超时返回后 handler 仍继续写入状态，重复重试造成副作用

传统线程池在超时后可能继续运行阻塞任务；如果上层立即重试写工具，会出现两次写入。仅记录
`timeout` 文本又无法证明是否真的发出了取消请求。

### 【根因分析】

执行器没有统一的生命周期和单调时钟，超时分支没有保存 `Future.cancel(true)` 的结果，异常也只
保留 message，无法区分工具超时、handler 失败与线程中断。

### 【解决方案/代码级实现】

`DefaultToolRegistry` 使用 `Executors.newVirtualThreadPerTaskExecutor()`，按定义 timeout 调用
`Future.get`；超时立即 `cancel(true)`，返回 `TIMED_OUT` 并把完整 `ToolTimeoutException` 堆栈和
`cancellationRequested` 写入审计。耗时由注入的 `LongSupplier nanoTime` 计算，关闭时 `shutdownNow()`，
关闭后的注册与执行直接拒绝。Registry 不自动重试，重试责任留给有预算约束的图节点。

### 【问题现象】工具失败被 Harness 当作节点成功，或 Hook 观测异常覆盖原始结果

如果治理失败只返回一个结果对象，现有 `NodeExecutionContext.callTool` 会继续发布 `AFTER_TOOL`；
如果观测 Hook 抛错直接向上冒泡，工具本身的成功/失败证据会被二次异常覆盖。

### 【根因分析】

Registry 结果协议与图生命周期协议没有桥接层，成功和非成功路径无法映射到不同 Harness 事件；
关键 Hook 与非关键 Hook 也没有隔离规则。

### 【解决方案/代码级实现】

`HarnessToolExecutor` 用 `NodeExecutionContext.callTool` 包裹 Registry。非 `SUCCEEDED` 结果在 action
内部抛出仅适配器可见、携带原结果的异常，使 Harness 发布 `FAILURE`，随后在外层还原原始
`ToolResult`；成功才发布 `AFTER_TOOL`。metadata 精确限定为 `toolName/callId/riskLevel`，不携带
arguments。现有 `HarnessHookChain` 对非关键 Hook 只写审计并继续，关键 BEFORE Hook 直接阻止 handler，
真实 StateGraph 集成测试验证了事件顺序和 runId/nodeName 一致性。

### 【证据】

`ToolRegistryTest`、`ToolRegistryTimeoutTest`、`ToolRegistryConcurrencyTest` 和
`ToolHarnessIntegrationTest` 覆盖注册唯一性、Schema/能力/审批拒绝、并发隔离、超时中断、完整堆栈与
Hook 失败边界。`ToolRegistryEddTest` 使用六个确定性场景生成
`agent-eval/target/edd/tool-registry-edd.json`，每项字段严格为
`taskId/status/audited/durationMs/errorType/passed`，全部场景通过且审计事件精确为一条。

## 第四篇 4B：MCP 远程工具协议与治理适配

### 【问题现象】JSON-RPC 字段漂移、重复字段或错误 ID 被当作成功响应

MCP 服务端可能同时返回 `result/error`、重复写入 `id`、追加第二段 JSON，或者用其他请求的
响应 ID 回答当前请求。宽松反序列化通常保留最后一个重复字段，使协议污染变成难以定位的
业务错误；初始化和工具调用还会在错误会话上继续运行。

### 【根因分析】

“JSON 能解析”不等于“JSON-RPC 2.0 合法”。握手、通知、分页发现和工具调用分别有精确字段集，
但通用 DTO 默认允许未知字段、尾随 token 和重复键；如果传输层同时承担业务解析，协议错误还会
被错误包装成 HTTP 故障。

### 【解决方案/代码级实现】

`McpJsonRpcRequest` 固定序列化 `jsonrpc/id/method/params`，notification 精确省略 `id`；
`McpJsonRpcResponse` 开启重复字段检测和尾随 token 拒绝，要求 `result/error` 二选一并校验精确 ID，
error code 必须是可转换为 Java int 的 JSON 整数，拒绝小数截断。`McpClient` 再分别校验
`initialize`、`tools/list` 和 `tools/call` 的结果结构、未知字段、重复工具名和循环 cursor；服务端
返回的 protocolVersion 必须与客户端请求值精确相等，版本不匹配时不发送 initialized notification。
传输异常与协议异常使用 `McpTransportException`、`McpProtocolException` 分离，完整 cause 保留到上层。

### 【问题现象】远程 Schema 不可信，批量发现可能留下半批本地工具

远程 `inputSchema` 可以包含本地运行时不支持的 `$ref` 或未知关键字。如果逐个发现、逐个注册，
第一个合法工具已经可见，第二个非法工具才失败，Registry 会进入难以重放的半完成状态；若执行时
才校验，handler 甚至可能在参数门禁前被调用。

### 【根因分析】

MCP discovery 返回的是外部输入，不是受信任的本地 `ToolDefinition`。只检查 Schema 是 JSON object
无法证明本地校验器理解其约束，批量注册也缺少预检阶段。

### 【解决方案/代码级实现】

`McpToolRegistryAdapter` 先完成 initialize 和完整分页 discovery，再用
`JacksonToolSchemaValidator` 对整批远程 Schema 做白名单预检，同时构造全部不可变
`ToolDefinition` 并检查本地名称冲突。`ToolRegistry.registerAll` 是原子批注册协议；
`DefaultToolRegistry` 在同一同步边界内预检整批自定义 Schema、批内重复名和已有名称，全部通过后才
写入 map。无效 Schema 测试同时返回一个合法工具和一个非法工具，分别覆盖 `$ref` 预检失败和注入
Registry 自定义校验器在第二项失败，两个场景都断言 Registry 最终为空。

### 【问题现象】namespace 冲突或格式修补导致模型调用了另一个工具

不同 MCP 服务都可能暴露 `echo`、`read` 等相同名称。若适配器自动小写、替换空格或删除符号，
两个远程工具会收敛到同一本地键，日志中的名称也无法还原真实远程调用。

### 【根因分析】

名称空间既是路由协议也是审计身份。对名称做模糊匹配或格式修补会破坏 Tool Registry 的唯一键，
并违反调用链对精确标识符的要求。

### 【解决方案/代码级实现】

本地工具名只允许精确拼接 `namespace + "." + remoteName`，不改变大小写、字符或段结构；namespace
点号只能分隔非空段，组合后的名称继续由 `ToolDefinition` 做长度和字符门禁。适配器在注册前查询
Registry 冲突，handler 闭包保存发现时的精确 `remoteName`，执行时不从本地名称反向猜测远程名称。

### 【问题现象】HTTP 超时返回后请求仍占用线程，SSE 响应被误当作单 JSON

只给 `RestClient` 设置连接超时不能限制完整 MCP 请求；服务端迟迟不返回或返回 SSE 时，调用线程
可能无限等待。仅中断调用方 Future 也不代表底层网络请求已经停止，随后重试会叠加在途请求。

### 【根因分析】

连接建立、响应读取和业务总预算是三个不同边界；当前 4B transport 只实现单个
`application/json` 响应，并不具备 Streamable HTTP/SSE 帧解析能力。把不支持的响应类型按普通 JSON
读取会制造伪成功。

### 【解决方案/代码级实现】

`McpHttpTransport` 使用 `RestClient.mutate()` 保留调用方配置的 base URL、认证头与拦截器，但强制
替换为无自动重试的 `SimpleClientHttpRequestFactory`，其 connect/read timeout 与 transport timeout
一致。请求在虚拟线程中执行，外层 `Future.get` 再做总预算门禁；超时立即 `cancel(true)`，以
`McpTransportException` 保留 cause。HTTP 非 2xx、空正文、非 JSON、SSE 和尾随 JSON 都明确失败。
回归测试给 503 POST 返回 `Retry-After` 并断言服务端只收到一次请求，同时断言 100ms 慢响应超时后
transport close 在 500ms 内结束。日志只记录 endpoint、method、requestId、HTTP 状态与 duration，
不记录参数、Authorization 或源码。SSE/Streamable HTTP 和 stdio transport 保持未实现状态，不伪造能力。

### 【问题现象】MCP 直连绕过 Registry，审批、能力和超时形同虚设

如果节点直接调用 `McpClient.callTool`，远程工具虽然可用，却绕过 Schema 校验、能力授权、HIGH 风险
审批、审计和统一超时。前端可能显示“等待审批”，远程副作用已经发生。

### 【根因分析】

协议 client 与治理入口职责混淆。MCP 只描述远程工具和调用协议，不提供 Agent4J 的用户、工作区、
风险或审批上下文，因此不能承担授权决策。

### 【解决方案/代码级实现】

`McpToolRegistryAdapter` 把远程 description、inputSchema 和精确 remoteName 映射为本地
`ToolDefinition`，风险、能力和 timeout 由调用方显式注入。节点只能通过 `ToolRegistry.execute`
执行：Schema、能力或审批失败时远程调用计数保持为零，成功时只调用一次，timeout 由 Registry 取消。

### 【问题现象】JSON-RPC error 与工具 `isError=true` 被混成同一种故障

JSON-RPC `error` 表示协议层请求失败，而成功的 `tools/call` 响应仍可能携带 `isError=true` 和结构化
content。若两者都只转成 message，远程返回的文本、图片或资源错误证据会丢失，修复循环无法判断
是传输故障还是工具执行失败。

### 【解决方案/代码级实现】

`McpClient` 对 JSON-RPC `error` 抛 `McpProtocolException`；对合法 `tools/call` 则完整返回
`McpToolCallResult(content, isError)`。适配器看到 `isError=true` 时抛
`McpRemoteToolException`，异常字段保留精确 remoteName 和防御性复制的 content JSON array，完整
堆栈由 Registry 写入 `ToolResult.errorStack` 与审计异常类型。

### 【证据】

`McpJsonRpcProtocolTest`、`McpHttpTransportTest`、`McpClientTest` 和
`McpToolRegistryAdapterTest` 覆盖严格协议、HTTP 错误、握手分页、精确名称、批量 Schema 预检、
权限审批、超时、重复注册和远程失败。`McpToolAdapterEddTest` 使用七个确定性场景生成
`agent-eval/target/edd/mcp-tool-adapter-edd.json`，每项字段严格为
`taskId/status/audited/durationMs/errorType/passed`。握手与发现没有执行 Tool Registry，`audited` 精确为
false，并通过协议请求计数与 notification 证明；成功、三类治理拒绝和远程失败各产生一条 Registry
审计事件，`audited` 精确为 true，七个场景均通过。

## 第四篇 4C：Skills 只读编排与渐进披露

### 【问题现象】把多个工具直接拼进 system Prompt，工具数量增长后上下文膨胀

天气查询、代码搜索和 MCP 工具各自可用时，如果每次请求都把全部 Schema、参数约束和实现说明
注入模型，简单问答也会承担完整工具上下文，首 token 延迟和输入 token 成本随工具数量线性增长。
更严重的是，模型看到未触发工具后可能主动选择不相关能力。

### 【根因分析】

Tool 是单一操作，Skill 还包含调用顺序、参数约束和异常策略；两者没有分层协议时，调用方只能
把“工具列表”和“能力知识”当作同一份静态字符串。教程第 14 章的三层懒加载如果没有确定的
披露边界，就会退化为全量 Prompt。

### 【解决方案/代码级实现】

`SkillDefinition` 把元数据、原始 trigger、有序 Registry 工具名和策略片段分开保存。
`SkillCatalog.resolve` 默认只返回 `SkillSummary` 的 discovery 分区；只有
`String.contains(exactTrigger)` 命中或显式传入精确 Skill 名称时，才返回 `ActivatedSkill`、
工具描述、canonical JSON Schema 和知识片段。每次上下文以 discovery/activation 两个分区的
UTF-8 SHA-256 生成 64 位指纹，供 Prompt 审计，不保存工具 handler 或参数正文。

### 【问题现象】触发词归一化造成错误 Skill 激活，多个版本选择不确定

将 trigger 自动 trim、折叠大小写或进行 Unicode 归一化，会让模型在用户没有表达该能力时激活
Skill；同一名称同时保留多个版本则会让命中结果依赖 Map 遍历顺序，审计无法说明实际使用的策略。

### 【根因分析】

标识符与自然语言触发文本的边界被混淆。模糊匹配对搜索体验有帮助，但不适合需要可重放的工具
编排；版本字段如果参与隐式排序，就会把发布策略偷偷变成运行时路由规则。

### 【解决方案/代码级实现】

4C 对 trigger 使用原始 Unicode `contains`，不 trim、不折叠大小写、不做归一化；相同精确 trigger
跨 Skill 直接在目录构造阶段拒绝。目录只允许一个精确 Skill 名称，`version` 是当前活动定义的
SemVer 审计标识，新版本通过装配层整体替换不可变目录，不在一次 resolve 中猜测版本。

### 【问题现象】Skill 直接调用 MCP 或生产类，审批与审计显示成功但远程副作用已发生

如果 Skill 保存 `McpClient`、Java 类名或脚本路径，模型可以绕过 4A Registry 的 Schema、能力、
HIGH 风险审批、超时和 ToolAuditEvent；前端即使显示“待审批”，远程工具也可能已经收到请求。

### 【根因分析】

Skill 组织层和工具执行层职责耦合。MCP 只定义发现与调用协议，不知道 Agent4J 的用户、工作区、
风险和审批上下文；反射和脚本入口还会把不可验证的动态代码带入核心模块。

### 【解决方案/代码级实现】

`SkillCatalog` 构造时仅从 `ToolRegistry.find(exactName)` 复制名称、描述和 Schema，未知工具或
Registry 读取失败导致整个目录原子失败。Skill 没有 `execute`、handler、反射或脚本字段；激活
结果产生的 `ToolCall` 仍必须交给 `ToolRegistry.execute`。4B MCP 适配器先把远程工具注册为本地
`ToolDefinition`，因此未批准 HIGH 风险时远程调用计数为零，批准后才允许一次调用。

### 【问题现象】默认发现结果泄漏完整策略、Schema 或敏感参数

把 Skill 的 Prompt 片段和工具参数放进常驻目录摘要，会让每次请求都暴露实现细节；将 EDD 报告
直接序列化完整 Prompt 又会把 Schema、源码或用户输入带入长期评测产物。

### 【根因分析】

“可发现”与“可执行元数据”没有独立协议。目录、激活上下文和评测报告若复用同一个对象，
渐进披露会在日志或报告序列化时被无意打穿。

### 【解决方案/代码级实现】

`SkillSummary` 只包含 name/version/description；`SkillToolMetadata` 对 JsonNode 在构造和 accessor
两端 deep copy；`SkillPromptContext` 分离 discoverySection、activationSection 和审计 fingerprint。
`SkillCatalogEddTest` 的报告字段严格为 `taskId/status/activatedSkills/exposedTools/fingerprint/passed`，
不写策略全文、Schema、工具参数或密钥。

### 【证据】

`SkillDefinitionTest` 覆盖核心 SemVer、精确文本和集合冻结；`SkillCatalogTest` 与
`SkillTriggerMatchingTest` 覆盖渐进披露、冲突、显式发现、大小写和 Unicode 语义；
`SkillCatalogConcurrencyTest` 验证 32 个虚拟线程并发读取和 Schema 隔离；
`SkillMcpIntegrationTest` 验证 MCP 工具经过 Registry 审批。`SkillCatalogEddTest` 七项全部通过并
生成 `agent-eval/target/edd/skill-catalog-edd.json`。

## 第四篇 4D：CLI Capability 治理与安全执行

### 【问题现象】把模型输出的自然语言直接交给 Bash，审批显示等待但终端已经启动

CLI 是 Agent 最容易产生外部副作用的能力。若节点把一整段自然语言或未经约束的 Shell
字符串交给 `SandboxTerminalService`，模型可以借助分号、管道、重定向、命令替换或环境
展开拼接第二条命令；若审批只在前端显示而没有位于终端调用之前，拒绝操作仍会落地。

### 【根因分析】命令意图、命令生成、安全策略和终端执行没有分层

原始 `CommandRequest` 只描述已经渲染完成的 Bash 字符串，不表达命令身份、风险、能力、
工作区或审批状态。调用方若自行拼接字符串，就会绕过统一的能力差集、HITL 决策、审计指纹
和路径边界。工作区只做 `normalize()` 也无法识别指向根目录外部的符号链接。

### 【解决方案/代码级实现】目录固定命令，意图只传 token，门面只执行 ALLOWED

`CliCommandDefinition` 固定精确命令名、executable、固定参数、`CliRiskLevel` 和
`RequiredCapability` 集合；`CliCommandIntent` 只接受精确命令名、用户 token 参数、
`workspaceRoot`、`TerminalTarget` 和有界 timeout。`CliCommandCatalog.authorize` 按“精确
查找 -> token 门禁 -> `toRealPath()` 边界 -> 单引号引用 -> SHA-256 -> 能力 -> 风险审批”
顺序生成 `CliCommandPlan`。`READ_ONLY` 自动允许，`MUTATING` 需要用户批准，`DESTRUCTIVE`
同时需要用户和管理员批准；缺少能力永远 `DENIED`，不能被审批覆盖。

`GovernedCliCommandExecutor` 将 `ALLOWED` 的同一个 `CommandRequest` 交给注入的
`TerminalCommandExecutor`，`DENIED` 与 `APPROVAL_REQUIRED` 返回空结果且调用计数为零。
底层 PTY/Docker 的 ANSI 日志、退出码、超时和异常 cause 原样保留，治理层不自动重试、不自动
批准、不拼接第二条命令。审计报告只保存渲染命令的 64 位小写 SHA-256，不保存命令正文或用户
参数。

### 【证据】单元、真实 PTY 与 EDD 同时验证策略和副作用边界

`CliCommandDefinitionTest`、`CliCommandCatalogTest` 与 `CliCommandRenderingTest` 覆盖集合
冻结、精确名称、三档风险、能力拒绝、参数注入、真实路径越界、符号链接逃逸和稳定指纹。
`GovernedCliCommandExecutorTest` 验证拒绝/待审批调用终端次数为零、允许路径只调用一次并
保留 Future 异常；`GovernedCliPtyIntegrationTest` 使用精确 `D:/Git/bin/bash.exe` 执行真实
`printf`，捕获 PTY 日志并确认日志读取线程为虚拟线程。

`CliCapabilityEddTest` 固定评测 `cli.read-only`、`cli.mutating-approval`、
`cli.destructive-admin`、`cli.capability-denied`、`cli.argument-injection`、
`cli.workspace-escape`、`cli.pty-output` 七条路线，报告严格只含
`taskId/status/decision/commandSha256/exitCode/timedOut/terminalCalls/passed`。
报告中的 `terminalCalls` 由 fake 或真实终端适配器实际计数，避免用授权结果推断副作用。

## 第五篇 5A：Multi-Agent Handoff 与状态所有权

### 【问题现象】模型可移交给任意自然语言目标，任务在 Agent 间无限接力

如果把模型输出的 Agent 名称直接交给运行时，大小写、别名或提示注入都可能把任务发往未授权
目标。只限制图最大步数也不能阻止跨子图反复 A→B→A；每次子图都有自己的步数预算，但整条调用链
仍会持续消耗线程、token 和时间。

### 【根因分析】Agent 目录、允许边和跨运行预算没有成为执行前的强类型门禁

单图的节点注册表只能证明节点存在，不能表达哪个 Agent 能把哪些状态交给哪个目标。若深度、剩余
次数和访问链只是 Prompt 文字，模型可以忽略；若依赖字符串模糊匹配，还会让同一输入无法稳定重放。

### 【解决方案/代码级实现】精确目录加白名单，深度、次数和访问环三重拒绝

`AgentDescriptor` 固定精确 `agentId`、`graphId`、只读输入键、拥有输出键和
`handoffTargets`；`AgentCatalog` 在构造阶段原子拒绝重复 Agent、未知目标和自移交。
`HandoffExecutionContext` 保存 `currentDepth/maxDepth/remainingHandoffs/visitedAgents`，每次
`descend` 同时检查深度、次数和访问环。`AgentHandoffExecutor` 在创建目标图之前完成白名单和上下文
校验，因此拒绝路线的图创建次数与 Trace 事件数都为零。

### 【问题现象】FORK 泄漏全部状态，FRESH 缺少任务 briefing

直接复制 `AgentState` 会把父 Agent 私有变量、历史 trace 和无关对话一起交给子 Agent；完全空白的
Fresh 子 Agent 又无法知道工作区和任务目标。验证 Agent 如果使用 Fork，还会继承主 Agent 的判断
偏差，重复给错误实现背书。

### 【根因分析】对话继承与状态授权被错误地绑定成同一个开关

Fork/Fresh 只回答“是否继承对话历史”，不能替代状态键权限。项目路径等执行输入仍需显式传递，
私有状态则无论哪种模式都不应泄漏。

### 【解决方案/代码级实现】对话模式与最小状态投影正交

`HandoffContextMode.FORK` 复制父 messages 后追加明确任务，`FRESH` 只创建一条任务 user message；
两者都只复制目标 `readableStateKeys`，父 trace 从不进入子运行。执行型子任务使用 Fork，独立审查
使用 Fresh，避免确认偏误；Fresh 的完整背景由 `AgentHandoff.content` 与显式只读输入共同提供。

### 【问题现象】子 Agent 越权修改父状态，结果合并发生静默覆盖

子图可以用 `withVariable` 写任意字符串键。如果执行完成后直接把 child variables 覆盖到父状态，
一个 Reviewer 就能改写 Coder 输出或工作区路径；两个子运行写同一键时，最后完成者还会无声覆盖
先完成结果。

### 【根因分析】`AgentState` 的不可变性只防止原地修改，不等于跨 Agent 所有权

Record 和 `Map.copyOf` 能保证对象不可变，却不知道哪个 Agent 有权写哪个键。没有先完整校验再合并
的两阶段过程，发现后续冲突时前面的键可能已经写入新状态。

### 【解决方案/代码级实现】只读键防篡改、输出键所有权和原子冲突检查

`AgentStateProjector` 记录初始子状态，完成后先验证所有只读键仍存在且值相同，再拒绝目标
`ownedStateKeys` 之外的新增/变更，确认所有 `requestedOutputKeys` 都存在。合并前先检查父状态：
不存在才写入、相同值保持、不同值抛 `AgentStateMergeException`；全部检查完成后才创建合并状态，
子 messages 与 trace 不合并，只向父 trace 追加不含正文的 handoff 标识。

### 【问题现象】子运行超时后 Future 已失败，阻塞虚拟线程却仍在后台运行

只对 `CompletableFuture` 调用 `orTimeout` 会让等待者得到超时，却不会可靠中断底层子图。阻塞节点
继续占用外部连接，随后重试会叠加重复副作用；子图请求 HITL 时若没有独立 Checkpoint，还可能被
误报为正常完成。

### 【解决方案/代码级实现】可取消 Future、独立 Run 标识和明确 HITL 失败

`AgentHandoffExecutor` 为每次子运行生成不同于父运行的 `childRunId`，在命名虚拟线程中创建独立
`StateGraph`。等待线程使用 `Future.get(timeout)`；超时立即 `cancel(true)`，由 `StateGraph` 取消
正在执行的节点并关闭图。嵌套中断转为 `AgentHandoffInterruptedException`，不伪造完成状态。
`AgentHandoffEvent` 分别记录 Started、节点开始/过程/完成、Completed 和 Failed，事件携带父子 Run
关联但不记录任务正文或状态值。

### 【证据】

`AgentCatalogTest`、`AgentHandoffTest`、`AgentStateProjectorTest` 和
`AgentHandoffExecutorTest` 覆盖精确目录、循环/预算、FORK/FRESH、状态越权、冲突、虚拟线程、
超时中断和嵌套 HITL。`MultiAgentHandoffEddTest` 固定评测
`handoff.fork`、`handoff.fresh`、`handoff.target-denied`、`handoff.cycle-denied`、
`handoff.depth-denied`、`handoff.state-ownership`、`handoff.merge-conflict`、`handoff.timeout`
八条路线，报告严格只含
`taskId/status/contextMode/fromAgent/toAgent/childRunDistinct/mergedKeys/eventCount/passed`。

## 第五篇 5B：StateGraph 拓扑校验与显式子图组合

### 【问题现象】图能成功构造，却在运行到死端、孤立节点或纯循环后才失败

原有 Builder 只检查节点和边的局部注册合法性。一个入口可达的节点可以没有出边，另一个节点
可以永远到不了 `__END__`，而条件环只有在模型选择特定路线后才暴露。若把这些错误留到运行期，
失败会消耗模型调用、沙箱资源和虚拟线程，且错误证据已经混入业务状态。

### 【根因分析】构造合法不等于拓扑可终止，条件路由也不能用一次运行代替结构分析

`Condition` 是运行时函数，不能在校验阶段执行或猜测返回值；但所有条件路由目标在 Builder 中已经
是精确声明。缺少不可变快照时，校验结果还可能受到后续 Builder 修改影响，审计无法重放。

### 【解决方案/代码级实现】快照分析与严格门禁分离

`GraphTopologyAnalyzer` 只读取注册节点和声明边，生成不可变 `GraphTopology`。入口 DFS 计算
`unreachableNodes`，空出边节点进入 `deadEndNodes`，从 `__END__` 反向 DFS 得到
`nodesWithoutEndPath`，Tarjan SCC 精确标记 `cyclicNodes`。条件环只要结构上存在 `__END__` 出口即
有效，纯循环仍交给 `ExecutionBudget` 在运行中以强类型 `ExecutionStopReason` 停止。
`StateGraph.inspectTopology()` 只返回证据，`validateTopology()` 才抛出携带完整快照的
`GraphTopologyException`；执行入口保持兼容，不自动改变既有预算语义。

### 【问题现象】把子图当作普通节点会泄漏全量状态，或把子图中断伪装成父图节点异常

直接复制 `AgentState` 会将父图的私有变量、消息和 trace 带入子图；直接覆盖合并又会让子图静默修改
父状态。另一方面，子图在 HITL 节点返回 `Interrupted` 后，如果调度器统一包装为
`GraphExecutionException`，上层无法恢复审批断点。

### 【根因分析】父子运行边界没有显式协议，调度器异常包装抹掉结构化语义

没有投影/合并端口时，状态所有权只能靠约定；没有独立子图 `GraphExecutionRequest` 时，runId、进度
和中断请求也无法关联。`StateGraph.executeNode` 的通用 `ExecutionException` 包装若不识别组合异常，
就会把可恢复中断降级为不可分类失败。

### 【解决方案/代码级实现】显式桥接、同 Run 关联、精确异常透传

`SubgraphStateBridge` 只提供 `project(parentState)` 与 `merge(parentState, childState)`，子图状态不
自动复制。`SubgraphNode` 先投影状态、创建一次独立 `StateGraph`、调用 `validateTopology()`，再以父
节点 `NodeExecutionContext.runId()` 创建子请求；子节点始终运行在独立虚拟线程执行器中。
父节点的 progress 发布器通过包级 `progressReporter()` 捕获，子图监听器只发布固定摘要：
`subgraph:<id>:started`、节点 started/progress/completed 和 `subgraph:<id>:completed`，避免递归
绑定子监听器。子图完成后才调用 merge；`GraphTopologyException`、`SubgraphInterruptedException`
和 `SubgraphExecutionException` 保留精确类型，后者完整保留底层 cause。调度器在 Future 解包时透传
这些结构化异常，HITL 不再伪装成顶层完成。

### 【证据】单元测试与 EDD 同时验证结构、线程和异常边界

`GraphTopologyTest`、`StateGraphTopologyTest` 和 `SubgraphNodeTest` 覆盖快照不可变性、条件环、无效
拓扑、父子状态隔离、同 RunId、虚拟线程、进度顺序、子图创建次数、失败 cause 与中断请求。
`StateGraphCompositionEddTest` 固定评测 `graph.linear`、`graph.react-cycle`、`graph.unreachable`、
`graph.dead-end`、`graph.no-end-path`、`graph.subgraph-bridge`、`graph.subgraph-interrupt`、
`graph.loop-budget` 八条路线，报告严格只含
`taskId/status/valid/unreachableNodes/deadEndNodes/nodesWithoutEndPath/cyclicNodes/stopReason/passed`。

## 第六篇 6A：框架对比与架构守卫

### 【问题现象】README 和设计文档声明“去框架化”，但构建文件可能悄悄引入 Agent 编排库

只靠代码审查或文档约定，无法阻止后续提交在 `agent-core/pom.xml` 添加 LangChain、LangGraph 或
其他 Agent 编排依赖；即使当前生产源码没有 import，依赖也可能通过自动配置、全局上下文或 transitive
artifact 改变核心边界。另一方面，框架对比如果只写概念名称，面试和维护者无法知道哪个类型真正
承担 State、Checkpoint、Tool 和 Runtime 职责。

### 【根因分析】依赖边界与概念映射没有成为可执行协议

POM 是结构化 XML，不能通过自由文本 grep 猜测依赖；源码、测试、README 和 AGENTS.md 的用途也不同，
把所有文本中的框架名称都当成违规会误报。没有固定端口清单时，映射文档还能在核心类型改名后继续
看似完整。

### 【解决方案/代码级实现】解析构建描述、限制扫描范围、固定自研端口清单

`ArchitectureConstraintTest` 使用安全 JAXP DOM 解析根 POM 和 `agent-core/pom.xml` 的
`<dependency>`，只对解析出的 `groupId:artifactId` 检查固定禁止片段
`langchain4j`、`langgraph4j`、`spring-ai`、`autogen`、`crewai`、`llamaindex`。源码门禁只递归
`agent-core/src/main/java`，拒绝设计中列出的精确 Agent 框架 import，不扫描文档和测试。
测试同时确认 `AgentState`、`Node`、`Condition`、`StateGraph`、`Checkpointer`、`ToolRegistry`、
`ModelRouter`、`AgentRunService` 八个核心端口文件存在。
`docs/ARCHITECTURE_MAPPING.md` 用表格记录这些自研类型与框架概念的边界，明确映射不是运行时依赖；
测试验证固定类型名称和“Agent4J 自研”声明，避免文档漂移。

### 【证据】架构守卫在本地构建中可重复执行

`ArchitectureConstraintTest` 的 4 个测试验证禁止依赖、禁止 import、核心端口和映射完整性；命令
`mvn -pl agent-core -Dtest=ArchitectureConstraintTest test` 在 JDK 21 下返回 4/4 通过。
本里程碑不修改生产代码或现有合法依赖，因此框架边界变化会在依赖引入的同一提交中直接失败。

## 第六篇 6B：受控 Agent Profile 与只读拓扑查询

### 【问题现象】为了追求低代码配置，Web API 容易演变成任意类名、表达式或图定义执行入口

如果 Controller 接收用户提交的 Java 类名、Spring Bean 名、路由表达式或完整 JSON 图，再由反射或
表达式引擎创建节点，调用方就能绕过构造器注入、工具能力掩码、workspace 权限和 HITL 门禁。动态图
即使只用于“预览”，只要复用了执行构造流程，也可能提前创建浏览器、PTY 或容器资源；配置内容还会
成为新的代码注入和审计重放边界。

### 【根因分析】把展示元数据、拓扑查询和运行时装配混成了一个可变协议

Dify / Coze 风格的产品界面需要展示 Agent 名称、能力、模型任务和图结构，但这些只读信息不等于用户
有权修改可执行对象。若 profile 标识通过大小写、别名或模糊匹配解析，同一请求可能映射到不同图；若
列表查询逐个创建图，又会把轻量元数据请求放大为基础设施资源消耗。

### 【解决方案/代码级实现】构造器注入 Profile，精确查找，查询图后立即关闭

`AgentProfile` 使用 record 固定 `profileId`、`graphId`、展示字段、`TaskType` 集合、能力集合和
`ExecutionBudget`；构造器冻结集合，不归一化任何标识或能力标签。`AgentProfileRegistry` 只接收
Spring 注入的 `AgentProfile` 与现有 `GraphRegistry`，按精确 `profileId` 查找并拒绝重复标识。
列表接口只读取声明元数据，不创建图；详情和拓扑接口通过 profile 中的精确 `graphId` 创建一次
`StateGraph`，调用 `inspectTopology()` 后使用 try-with-resources 立即关闭，节点执行次数保持为零。

Web 层只提供 `GET /api/agent-profiles`、`GET /api/agent-profiles/{profileId}` 和
`GET /api/agent-profiles/{profileId}/topology`。未知 profile 使用
`AgentProfileNotFoundException`，未知 graph 继续使用 `GraphNotFoundException`，两者都进入既有
404 ProblemDetail；空白标识进入 400。项目没有新增 POST/PUT/PATCH/DELETE，也没有反射、表达式或
用户提交图定义的入口。

### 【证据】核心生命周期和 Web 合约分别验证

`AgentProfileRegistryTest` 验证元数据不可变、精确查找、稳定排序、单次图创建、拓扑读取不执行节点
以及未知 graph 透传；`AgentProfileControllerTest` 验证列表无图检查副作用、详情与拓扑 JSON、空白
标识 400、未知 profile/graph 404。`AgentWebApplicationTest` 验证示例环境实际装配
`demo-agent` Profile，并精确关联同名 graph。

## 第七篇 7A：受治理 CLI Agent 与真实失败自愈

### 【问题现象】Coder 返回裸 Bash，工具目录和知识证据没有进入生产执行链

早期生产图虽然已有 `ToolRegistry`、`CliCommandCatalog` 和项目知识上下文，`CoderNode` 仍直接调用
`AstService.applyDiff`，并把模型返回的 `command` 原样写入 `ops.command`。这使工具 Schema、能力
授权和审计端口只存在于独立测试中；模型还能利用 Shell 控制字符追加第二条命令。Coder 也没有把
Planner 已确定的知识指纹和来源数带入自身证据，修复循环无法证明第二次修改依据了同一项目规则。

### 【根因分析】能力组件存在不等于生产拓扑已建立强类型交接

节点之间仍以自由字符串共享命令，工作区路径同时出现在状态和潜在模型参数中。`AgentState` 的不可变
Map 只能防止原地修改，不能阻止模型绕过目录，也不能保证 Diff 一定经受治理工具执行。代码修改路由
还只声明 `CODE_READ/CODE_WRITE`，与生产拓扑必经 Ops 的事实矛盾，正确的 CLI 定义若要求
`TERMINAL` 会立即拒绝。

### 【解决方案/代码级实现】补丁工具、结构化意图和精确生产目录组成单向链

内置 `code.apply-diff` 只接受 `unifiedDiff`，`workspaceRoot` 从 `ToolInvocationContext` 绑定，
`additionalProperties=false` 拒绝模型提交路径。Coder 的严格 JSON 协议改为
`summary/unifiedDiff/commandName/commandArguments`，未知 `command` 字段直接失败；补丁通过
`HarnessToolExecutor` 和 `ToolRegistry` 应用，非成功 `ToolResult.errorStack` 原样进入
`coder.error`。Coder 同时记录 Planner 知识指纹、来源数，并把结构化命令交给 Ops。

生产配置只注册 `test.cat` 与固定 `mvn test` 的 `test.maven`，二者都要求 `TERMINAL`，模型不能
扩展目录。代码修改快路由同步声明 `TERMINAL`，Ops 只有在目录完成精确名称、参数控制字符、真实
workspace 路径、能力和风险授权后，才把渲染命令写入 `ops.command` 并创建终端 Future。

### 【问题现象】把待审批计划放在内存 Map，服务重启后批准恢复失效

首次实现曾在 `CliApprovalInterruptPolicy` 内缓存 `runId -> commandSha256`。这在单 JVM 测试可用，
但 WAITING_APPROVAL 已持久化到 PostgreSQL；若服务在审批前重启，内存记录消失，恢复后的 Ops 会再次
得到 `APPROVAL_REQUIRED`，形成无法完成的审批循环。

### 【解决方案/代码级实现】把既有一次性 bypass 作为批准信号，恢复时重新授权

`StateGraph` 将 `GraphExecutionRequest.bypassInterruptAtStart` 精确绑定到当前节点的
`NodeExecutionContext.approvalBypassed()`，只在恢复起始节点可见，进入下一节点立即清理。策略不保存
审批正文或进程内状态；Ops 在批准恢复时用原状态重新解析 `CliCommandIntent`、重新渲染命令和
SHA-256，再用批准上下文授权。拒绝路线不调度图，普通直接调用没有 bypass，因此永远不能越过
MUTATING/DESTRUCTIVE 门禁。

### 【问题现象】EDD 已运行两轮，但 Reviewer 一直失败并触发最大步数

真实 EDD 的 Mock HTTP 分派最初在原始 JSON 请求体中查找实际换行。Prompt 换行经过 JSON 编码后是
字符序列 `\n`，修复分支与通过分支都未命中；这类测试会错误地把响应脚本缺陷归因于 Agent 自愈
能力。

### 【解决方案/代码级实现】按传输层精确编码匹配，并验证真实外部证据

响应分派按原始 JSON 中的 `\n` 精确匹配 `ops.exitCode`。`CliAgentWorkflowEddTest` 使用真实临时
Git 工作树、JGit 两次连续 Diff、`D:/Git/bin/bash.exe`、pty4j ANSI 日志和受治理 `test.value`
命令：首轮把值改成 `broken` 并得到非零退出码，第二轮从 Ops/Reviewer 证据修复为 `after`。
最终 Trace 精确为 `planner/coder/ops/reviewer/coder/ops/reviewer`，报告只含
`taskId/status/attempts/updatedFiles/commandSha256/terminalCalls/passed`，实际结果为两次终端调用、
两次 Coder 尝试和 `passed=true`。

## 第七篇 7B：GUI Agent 页面动作与证据闭环

### 【问题现象】多个 Run 共享 Playwright Page，页面状态和关闭动作互相污染

Reviewer 时代的单例浏览器适合顺序审查，却不能直接供 GUI Agent 并发执行。两个 Run 若共享同一个
Page，其中一次导航、表单填充或会话关闭会改变另一次运行正在观察的页面；审计里的 `runId` 即使正确，
DOM 和截图仍可能来自另一条执行链。

### 【根因分析】Spring Bean 生命周期不等于浏览器任务生命周期

Playwright 的 Browser、Context 和 Page 具有状态与线程亲和性。把单例服务当成无状态工具，会让
Tool Registry 的 Run 隔离停留在审计字段层面，实际外部资源没有隔离。

### 【解决方案/代码级实现】每个 Run 独占会话，所有动作按 Run 精确取回

`BrowserSessionRegistry` 以精确 `runId` 管理 `BrowserAutomation`，重复 open 和未知 Run 均直接
失败。`browser.navigate/click/fill/scroll/evidence` 只能从 `ToolInvocationContext.runId()` 取得
当前会话，不能绕过注册表访问 Playwright。`GuiAgentNode` 在 `finally` 中关闭该 Run 会话；关闭失败
不会覆盖原执行异常，而是作为 suppressed exception 保留完整因果链。

### 【问题现象】Locator DOM 与截图范围不一致，模型引用的证据无法复核

若 locator 模式仍返回整页 HTML，却只截取某个元素，截图哈希与 DOM 哈希描述的是不同范围。模型可以
根据整页文字宣称元素操作成功，但审计人员无法用同一证据对象复核该结论。

### 【根因分析】证据选择器只约束截图，没有同时约束结构化页面证据

截图和 DOM 是同一次观察的两个视图，必须共享精确范围。只把 CSS selector 传给 screenshot，而对
DOM 固定调用 page content，会破坏证据对象的原子语义。

### 【解决方案/代码级实现】Page 与 locator 使用一致证据边界

`BrowserEvidenceSelector` 精确区分 `page` 和非空 locator。`PlaywrightBrowserService.capture` 在
page 模式返回整页 HTML 与全页 PNG，在 locator 模式返回同一 locator 的 `outerHTML` 与元素 PNG；
`BrowserEvidence` 同时携带最终 URL、选择器、DOM、截图和两类 SHA-256，前端、模型和审计报告引用
同一个证据 ID。

### 【问题现象】Future 超时后 Playwright 动作仍继续，或 Playwright 先超时但工具线程未结束

GUI 操作同时跨越工具调度线程和 Playwright 专属线程。只有 Tool Definition timeout 时，外层 Future
虽然失败，底层定位或点击仍可能继续；只有 Playwright timeout 时，外层等待也可能因异常传递缺陷而
长期占用虚拟线程。

### 【根因分析】调度超时与浏览器操作超时属于两个不同资源边界

Tool Registry 控制一次工具调用能占用多久，Playwright timeout 控制页面 API 等待元素或导航多久。
两者不能互相替代，且失败必须保留底层 Playwright cause 才能区分元素缺失、导航失败和调度超时。

### 【解决方案/代码级实现】工具硬上限与 Playwright 操作上限双层治理

五个浏览器工具都声明 Definition timeout，并把同一正 `Duration` 传入导航、点击、填充、滚动和证据
采集 API。工具执行超时取消内部 Future，Playwright API 同时设置毫秒级操作 timeout；异常通过
`ToolResult.errorStack` 或 `gui.error` 完整保留，不把超时改写成普通空结果。

### 【问题现象】模型输出 done，却没有能证明结果的页面证据

视觉模型可能在动作尚未生效时直接返回成功摘要。若节点只检查 `action=done`，页面未变化也会写入
`final_response`，形成对用户可见的虚假成功。

### 【根因分析】模型结论没有与运行时已采集证据建立引用完整性约束

自然语言摘要无法证明其依据了哪次 DOM 和截图。即使 Prompt 要求模型引用证据，也必须由运行时验证
引用是否存在，不能相信模型自行遵守。

### 【解决方案/代码级实现】严格动作协议与已存在证据引用门禁

`BrowserActionDecision` 拒绝 Markdown fence、未知字段、错误类型和动作专属字段越权；模型只能通过
严格 Schema 的唯一 `browser_action` Function 返回动作。`done` 必须包含非空 summary、至少一个已采集
的 evidenceRef，且 summary 必须逐字出现在被引用证据 DOM 中。`GuiAgentNode` 只在所有引用属于当前 Run、
页面证据支持结论且会话清理成功后写入 `final_response`，否则写入完整 `gui.error`。

### 【证据】真实 Chromium EDD 覆盖动作、DOM、PNG 和审计链

`GuiAgentWorkflowEddTest` 启动真实本地 `HttpServer`，通过真实 Playwright Chromium 执行
`navigate -> page evidence -> fill -> locator evidence -> page evidence -> click -> locator evidence -> page evidence -> done`。
测试断言最终 DOM 为 `submitted: Agent4J`、截图具有 PNG 文件签名、八次 Tool Registry 审计顺序精确、
最终摘要引用 `evidence-4`，并确认状态中不存在 Coder/Ops 输出。EDD 报告严格只含
`taskId/status/steps/toolCalls/evidenceRefs/finalUrl/domSha256/screenshotSha256/passed`。

### 【问题现象】契约型 EDD 通过，但供应商监控没有请求记录，无法说明真实模型质量

浏览器工作流 EDD 为了稳定验证 Playwright、Tool Registry 和证据门禁，使用本地页面和确定性模型响应。
它会产生真实 Chromium、DOM、PNG 和工具审计证据，但不会访问外部 LLM；若把这类 EDD 当成模型质量
评测，供应商监控为空并不奇怪，也无法发现真实模型的路由格式、回答质量和延迟问题。

### 【根因分析】工程契约验证与真实模型评测混成一个测试门禁

外部 API 有网络、配额、模型输出漂移和成本边界，不能让普通 `mvn test` 隐式依赖它；但只保留 Mock
又会让生产端点从未被真实调用。两类测试若没有显式名称和开关，测试报告无法区分“链路正确”和“模型
实际可用”。

### 【解决方案/代码级实现】双层 EDD：默认隔离，显式真实调用

`GuiAgentWorkflowEddTest` 固定验证浏览器执行协议；`LlmEddTest` 读取精确的
`AGENT_LLM_ENABLED`、`AGENT_LLM_BASE_URL`、`AGENT_LLM_API_KEY` 和四个模型变量，只有开启后才
调用 OpenAI 兼容端点。真实评测报告写入 `agent-eval/target/edd/llm-edd-<timestamp>.json`，不提交
API Key 或完整回答。2026-08-09 的真实运行命中 `https://zz.cxwms.com`，6 个场景全部通过，路由为
`chat/chat/chat/agent/agent/agent`，测试结果为 2/2、0 失败、0 错误、0 跳过。

### 【问题现象】真实模型正确返回 agent，却被 EDD 夹具的缺失状态键误报为链路失败

第一次真实运行已产生 HTTP 200 和模型响应，但 `code.intent` 及两个记忆场景在 Planner 进入代码路由
时失败，错误为 `缺少状态变量: coder.workspacePath`。如果只看测试最终断言，会误以为模型或路由器失败，
丢失了真正的测试装配错误。

### 【根因分析】测试状态没有复用生产节点的精确输入契约

`PlannerNode.execute` 对 agent 路由明确读取 `planner.repositoryId`、`planner.userId` 和
`coder.workspacePath`。`LlmEddTest` 当时只设置前两个键，导致模型调用完成后仍无法构造代码规划上下文。

### 【解决方案/代码级实现】用生产常量补齐 EDD 状态，不放宽生产校验

测试现在通过 `CoderNode.WORKSPACE_PATH_KEY` 写入当前 EDD 工作目录的绝对规范化路径，保留 Planner
对缺失状态的严格拒绝语义。修复后再次真实调用端点，6 个场景和 RAG 增强测试均通过；这证明报告中的
绿灯同时覆盖了模型请求、Planner 路由、记忆/知识上下文和协议解析，而不是仅由 Mock 响应驱动。

### 【问题现象】生产工具执行成功，但滚动日志没有任何工具审计事件

代码层的 `DefaultToolRegistry` 已经支持 `ToolAuditSink`，测试也能收集每次调用；生产配置却曾经
使用 `ToolAuditSink.noop()` 兼容入口。结果是浏览器动作真实执行了，审计字段却只停留在内存测试，
线上无法按 `runId`、用户、节点和工具定位一次高风险操作。

### 【根因分析】测试注入的观测器和生产 Bean 装配不是同一条路径

接口存在不代表生产链路接通。直接构造图的测试为了简化依赖调用了 noop 重载，生产 Spring Bean 又复用
了该重载，导致审计副作用被静默丢弃。

### 【解决方案/代码级实现】生产注入 Logback 审计端口，兼容入口只用于隔离测试

`ProductionGraphConfiguration.productionToolAuditSink` 注入真正的 `ToolAuditSink`，把完整
`ToolAuditEvent` 的 `runId/nodeName/userId/callId/toolName/risk/status/durationMs/argumentsSha256/
errorType/cancellationRequested` 写入现有控制台和滚动文件。四参数直接构造入口显式保留 noop，
但生产 `@Bean` 必须走注入重载；`ProductionGuiAgentIntegrationTest` 通过真实 Spring 装配验证事件已
抵达 sink。敏感参数只记录 SHA-256，不把值写入日志。

### 【问题现象】并发 open/close 或关闭失败时，浏览器会话泄漏或无法重试清理

两个虚拟线程同时为同一 Run 打开会话可能覆盖句柄；`close(UUID)` 先从 Map 移除再调用 Playwright，
一旦 close 抛错，后续清理已经找不到会话。工厂若把同一活跃 Browser 实例返回给两个 Run，也会造成页面
状态交叉污染。

### 【根因分析】Map 的并发安全不等于资源生命周期的原子性

创建、登记、关闭和移除必须是同一个生命周期协议。仅使用并发容器不能把“拿到句柄”和“清理成功”绑定
起来；关闭失败的句柄必须继续归注册表所有，才能进行重试。

### 【解决方案/代码级实现】统一生命周期锁与成功后移除

`BrowserSessionRegistry` 用同一 `synchronized` 生命周期锁串行化 open、精确 close 和 close-all；
只有 `BrowserAutomation.close()` 成功后才 `remove(runId, session)`，失败句柄保留并在下一次 close
重试。注册时拒绝工厂返回已经属于其他 Run 的同一实例。`BrowserSessionRegistryTest` 覆盖复用拒绝、
关闭失败重试、并发 close 和重复 close。

### 【问题现象】Playwright 操作传入纳秒级正 Duration，却变成“无超时”

`Duration.toMillis()` 对小于 1ms 的正值返回 0；Playwright 将 0 解释为禁用超时。调用方以为已经设置
边界，浏览器 API 却可能无限等待。

### 【根因分析】业务时间精度和第三方 API 的整数毫秒协议不一致

只检查 `Duration` 非负不足以证明底层配置有效，转换后的值也必须满足 Playwright 的最小协议单位。

### 【解决方案/代码级实现】转换后再次验证并设置 Page/Context 默认上限

`PlaywrightBrowserService.validateTimeout` 在 `toMillis()` 后拒绝小于 1ms；导航、点击、填充、截图、
DOM evaluate 和 locator evaluate 使用显式 timeout，并为不支持单独 timeout 的滚动 API 设置 Page 与
BrowserContext 默认 timeout。`PlaywrightBrowserServiceTest` 以 1ns 回归用例锁定该边界。

### 【问题现象】关键 Harness Hook 抛错穿透节点，Web 端只看到图执行异常，没有 `gui.error`

GUI 工具的 BEFORE/AFTER/FAILURE Hook 属于运行时审计边界。关键 Hook 失败如果直接重新抛出，
`GuiAgentNode` 无法写入状态错误字段，前端也不会得到可恢复的节点证据；浏览器会话还可能继续占用。

### 【根因分析】Hook 异常与普通节点异常没有统一状态出口

通用图引擎可以包装异常，但 GUI 节点需要在关闭专属会话后把完整堆栈放入自己的状态协议，不能让异常
包装层替代业务错误字段。

### 【解决方案/代码级实现】所有 GUI 失败统一写 `gui.error` 并禁止最终响应

`GuiAgentNode.executeGui` 捕获关键 Hook、工具、Playwright、模型协议和清理异常，先关闭当前 Run 会话，
再写完整堆栈到 `gui.error` 与 `gui` trace；失败路径不写 `final_response`。清理异常附加到首个失败的
suppressed 列表，保证审计和资源治理信息都可追踪。`GuiAgentNodeTest` 的关键 Hook 拒绝用例验证该状态
契约。

### 【问题现象】契约型 GUI EDD 通过，但供应商监控没有请求；真实 EDD 又被模型输出和视觉能力打穿

确定性 EDD 使用 Mock 模型只能证明真实 Chromium、工具 Schema、审计和证据哈希。第一次 Live GUI EDD
加载根目录 `.env` 后确实命中 `https://zz.cxwms.com/v1/chat/completions`，但探测发现同一
`gpt-5.4-mini` 的纯文本请求 HTTP 200，最小 PNG 多模态请求 HTTP 400，完整截图请求 HTTP 500；后续
真实调用还暴露了自由 JSON 的 `summary/reason/unknown field` 漂移以及局部证据遮蔽全局结果。

### 【根因分析】三个边界被错误地当成一个问题

第一，外部模型是否支持图片是端点能力问题；第二，自由文本 JSON 是否遵守动作协议是输出约束问题；
第三，点击按钮后的结果是否出现在指定 locator 是证据范围问题。只改 Prompt 或只增加截图都不能同时
解决三者，也不能让普通 Maven 测试隐式依赖外部服务。

### 【解决方案/代码级实现】视觉优先、DOM 降级、Function Calling 与双层证据门禁

`GuiAgentNode` 先提交真实多模态请求；路由失败或 HTTP 200 但动作协议无效时，沿同一
`TaskType.VISION` 路由用 DOM 文本重试，两次失败保留 suppressed 异常。模型决策改为强制唯一
`browser_action` Function，函数定义声明 `strict=true`、`additionalProperties=false`、八个字段全部
required，并用 `anyOf` 精确表达 click/fill/scroll/done 的字段约束；节点拒绝正文动作、错误函数名和多个
ToolCall，只解析函数 arguments。每次局部证据后追加一次 page 证据，防止 `#submit` 的局部 DOM 隐藏
`#result` 的变化；`done` 必须引用最新 page 证据，且 summary 逐字出现在该证据的 `innerText` 中，才允许
写入 `final_response`。

### 【证据】真实模型、真实浏览器和真实审计均已命中

2026-08-09 的 Live GUI EDD 使用根目录 `.env`、真实 `gpt-5.4-mini`、真实 Playwright Chromium、
临时本地表单和生产同构 Tool Registry。最终一次执行产生 3 次 HTTP 200 模型请求（每次均记录
input/output tokens、状态码与耗时），完成真实 `browser.fill`、`browser.click`、page DOM/PNG 证据
采集，最终 DOM 包含 `submitted: Agent4J`，报告 `mode=LIVE`、`status=COMPLETED`、`passed=true`。
报告只保留 endpoint/model、步数、工具次数、最终 URL 与证据 SHA-256，不落盘 API Key、Prompt、完整
Completion 或截图正文；失败运行仍保留 Surefire 堆栈供审计复盘。

### 【问题现象】浏览器清理失败后注册表仍保留句柄，但真实 Playwright 服务无法再次清理

注册表只有在 `BrowserAutomation.close()` 成功后才移除 Run。若 Playwright 的 Page 或 Context 第一次
关闭抛错，旧实现却在第一次调用就把 `closed` 设为 true、关闭 executor，后续重试直接 return；注册表的
句柄虽然还在，实际 Chromium 资源已经失去清理入口。

### 【根因分析】资源所有权重试协议与底层实现的幂等状态不一致

上层把“关闭失败”视为可重试，底层却把失败路径和成功路径都标成终态，并且无界等待 cleanup future。
这同时造成泄漏风险和图节点永久挂起风险。

### 【解决方案/代码级实现】逐资源成功后置空、失败保留，清理等待有硬上限

`PlaywrightBrowserService` 只有整个资源链清理成功后才设置 `closed=true` 并关闭 executor；Page、Context、
Browser、Playwright 各自只有 close 成功后才清空字段，失败对象在下一次调用中继续尝试。cleanup future 使用
固定关闭上限；超时后保留仍在运行的清理句柄，后续调用不会启动并发清理，任务结束后才允许重试，并返回
`BrowserAutomationException`。`PlaywrightBrowserServiceTest` 验证第一次失败、第二次成功、超时和不可取消
清理任务路径；`BrowserSessionRegistryTest` 验证上层仍保留失败句柄。

### 【问题现象】GUI 状态随着每个局部证据重复保存完整 DOM、可见文本和 Base64 PNG

每次 fill/click 后同时采集 locator 与 page 证据。若把 `browser.evidence` 的完整输出原样追加到
`gui.evidence`，多步流程会把 checkpoint、SSE 和下一轮 Prompt 迅速放大到 MB 级，历史截图也重复消耗
数据库和网络带宽。

### 【根因分析】证据引用元数据和证据正文生命周期不一致

动作使用稳定 ID、URL、selector 和哈希来审计，但 Web 画廊和断点恢复还必须能通过同一个 evidence ID
恢复 DOM、可见文本和截图。只保留最新正文会让历史引用变成无法读取的“悬空 ID”；原始工具输出的单项预算也
不能替代 Run 级状态设计。

### 【解决方案/代码级实现】工具出口预算 + 每条证据独立保留正文

`BrowserToolDefinitions` 对 DOM、visibleText 和 PNG 施加精确预算（64,000 code points、16,384 code
points、4 MiB）；超限 PNG 直接失败，截断文本后重新计算对应 SHA-256。`GuiAgentNode.captureEvidence`
为每个 evidence ID 保留受节点上限约束的 DOM、visibleText、截图 data URL 与对应哈希，当前证据仍单独写入
`gui.dom`/`gui.screenshotDataUrl` 供下一轮模型观察。这样历史引用、前端画廊和审计记录都指向同一份可恢复
证据，而不会依赖“最新状态”猜测正文。

### 【问题现象】非多模态网关返回 HTTP 200，但正文没有合法动作，Agent 直接失败

部分供应商对图片请求并不返回 4xx，而是返回普通文本或错误 ToolCall。若降级只捕获
`ModelRoutingException`，HTTP 200 的协议漂移不会进入 DOM 文本路径。

### 【根因分析】传输成功不等于 Agent 协议成功

HTTP 状态、模型响应结构和业务动作协议是三个独立边界。只在传输层判断失败会把供应商能力差异暴露给
节点，而不是转成可恢复的同一任务路由。

### 【解决方案/代码级实现】把动作解析纳入同一 VISION 降级边界

`GuiAgentNode.completeDecision` 在保存原始 ToolCall arguments 后解析严格动作；模型返回正文、错误函数名、
多 ToolCall 或非法 JSON 时抛出 `DecisionProtocolException`，与 `ModelRoutingException` 一样触发 DOM 文本
重试。`GuiAgentNodeTest` 用 HTTP 200 普通文本回归了这条路径，并验证最终响应不丢失。

### 【问题现象】Live EDD 初始化中途失败会留下本地 HTTP Server 或模型/工具 executor

真实 EDD 先启动页面、再创建会话、Tool Registry 和 LLM Client。若任一步骤抛错，尚未进入原有
try-with-resources 的对象不会自动关闭，测试进程可能残留端口、虚拟线程和浏览器句柄。

### 【根因分析】资源声明晚于资源创建

try-with-resources 只管理已经成功完成资源声明的对象；多资源初始化写在资源块外时，部分成功的前缀没有
统一 finally。这个问题不会在通过场景暴露，只在供应商错误或 Chromium 启动失败时出现。

### 【解决方案/代码级实现】可空句柄 + finally 全量清理

`LiveGuiAgentWorkflowEddTest` 将 sessions/tools/client 初始化放入受保护的 try，finally 中逐个关闭并停止
本地 `HttpServer`；清理辅助方法即使某一资源 close 抛错也继续关闭后续资源，并将后续异常作为 suppressed
保留。状态失败仍生成脱敏 LIVE 报告。这样真实 API、浏览器和审计资源在成功与异常路径均有明确生命周期。

## 第八篇 23：Evaluation 能力集与 CI 门禁

### 【问题现象】Java 单元测试全部通过，但无法回答 Agent 能力是否稳定、轨迹是否正确以及成本是否超预算

原有 `BenchmarkReport` 只聚合任务通过率、`pass^k` 和 TTFT。CLI、GUI、RAG 与模型 EDD 各自生成独立 JSON，
没有统一能力维度、工具/节点轨迹、token/cost 和失败类型，因此“绿色构建”不能作为质量门禁。

### 【根因分析】执行结果和评测遥测被错误地设计成同一个生命周期

把供应商 token、费用和 Prompt 直接塞进 `BenchmarkTaskResult` 会破坏现有执行器契约；把外部 EDD 强行放入
普通 Maven 测试又会让 CI 依赖网络、配额和真实密钥。两种极端都会让报告不可复现或泄露敏感数据。

### 【解决方案/代码级实现】独立 Evaluation 层通过精确任务键关联脱敏观察

`EvaluationSuite` 用 `Map<taskId, capabilityId>` 显式绑定每个任务，拒绝未知、缺失和重复能力映射；
`EvaluationObservation` 用 `(taskId, repetition)` 作为唯一键保存事件轨迹、input/output tokens、费用和
`FailureCategory`，不接受换行、Bearer 或密钥样式的故障正文。`EvaluationScorer` 先复用原始
`BenchmarkMetrics`，再按能力计算 passK、轨迹有序子序列、TTFT P95、费用和失败分类，缺失观察不会被补成零。

### 【问题现象】单个全局 passK 通过，某个关键能力却持续退化

不同章节任务混在同一个总体比例中时，大量简单问答可以掩盖 CLI 修复或 GUI 证据链的失败；只看总体指标无法
定位是路由、工具协议、权限还是超时问题。

### 【解决方案/代码级实现】能力级阈值和稳定 CI violation 顺序

`EvaluationReport` 冻结 `EvaluationGatePolicy`，能力指标同时保留 `requiredMinPassK` 与 `maxTtftP95`。
`EvaluationGate` 先按固定顺序检查 `passK/ttftP95/costUsd/failureCount`，再检查按 ID 排序的能力阈值，返回
`EvaluationGateResult`。`EvaluationGateViolationException` 只包含指标、实际值和限制，不写 Prompt、API Key
或完整回答；`BenchmarkReportWriter` 输出 `deterministic|live`、modelCallAttempts、能力指标和门禁结果的
脱敏审计信封。

### 【证据】第 23 章确定性 EDD 覆盖三类能力和报告门禁

`EvaluationEddTest` 构造 50 项 CLI、GUI、RAG 能力任务，提供确定性节点轨迹和遥测，实际写入
`target/edd/evaluation-chapter-23.json`，断言 3 个能力、`modelCallAttempts=0`、成本/token 汇总和
`gate.passed=true`。真实模型 EDD 仍由既有显式 `AGENT_LLM_ENABLED` 开关控制，不会被普通构建伪装成真实
供应商调用。

## 第八篇 24：Agent Security 与红队门禁

### 【问题现象】用户任务、项目知识或工具输出中的伪指令可能改变 Agent 控制规则

如果把所有文本直接拼入 Planner Prompt，诸如“忽略之前的系统指令”“输出隐藏 Prompt”或外部页面要求
修改审批策略的内容会与可信指令混在一起。工具参数还可能携带控制字符、`Bearer ` 或 `sk-` 凭据，
工具输出则可能在嵌套对象中泄露 `authorization`、`token` 等字段。

### 【根因分析】模型输入、工具权限和输出审计缺少统一的强类型边界

仅依赖 Prompt 约束无法保证模型遵守安全规则；仅在 Web 层过滤也会遗漏直接调用核心工具的路径。工具
参数如果按字符串猜测工具名或字段名，容易产生误放行；违规记录若保留原文，又会把攻击载荷和密钥写入
数据库与日志。

### 【解决方案/代码级实现】固定规则检测、精确 JSON Pointer 策略和递归脱敏

`DefaultPromptInjectionDetector` 对精确的 `user.task`、`project.knowledge`、`tool.output` 来源返回
`ALLOW/FLAG/BLOCK` 和固定规则 ID；Planner 在任何模型请求前执行检查，`BLOCK` 直接走
`planner.route=failed`，`FLAG` 只推送 `ruleId/severity/source` 摘要。`DefaultToolParameterPolicy`
使用工具名和 JSON Pointer 白名单拒绝未声明字段、控制字符及凭据格式；`DefaultOutputRedactor` 深拷贝
JSON 并递归替换敏感字段和值。`DefaultToolRegistry` 固定执行顺序为 Schema、参数策略、授权、Handler、
输出脱敏，并把拒绝原因写入脱敏的 `SecurityViolation`。

### 【问题现象】安全违规只写在内存或控制台，无法按 Run、用户和节点审计

安全端口存在但没有生产装配时，模型或工具确实被拒绝，数据库却没有记录，出现问题后只能依赖短暂的
进程日志定位。

### 【根因分析】安全策略与持久化适配器没有共享同一条 Spring 生产装配路径

测试中的 Sink 收集器和生产图的 `noop` 兼容构造器不是同一个依赖图；即使核心逻辑正确，生产配置仍可能
静默丢弃违规事件。

### 【解决方案/代码级实现】PostgreSQL 作为安全违规权威记录源

Flyway `V3__security_violations.sql` 创建 `agent_security_violations` 表，并按 `(run_id, occurred_at)`
和 `(user_id, occurred_at)` 建索引。`JdbcSecurityViolationSink` 使用 `TransactionTemplate` 原子写入
固定字段；持久化失败抛出明确的 `SecurityPersistenceException`。生产 `ToolRegistry` 与 `PlannerNode`
通过 Spring 注入同一个 JDBC Sink，直接构造的测试重载才使用 `noop`，从而保持隔离测试的无外部副作用。

### 【证据】第 24 章确定性红队 EDD 与真实 PostgreSQL 迁移

`SecurityRedTeamEddTest` 使用真实 Prompt 检测器、参数策略、输出脱敏器和 `DefaultToolRegistry`，覆盖
20 项攻击与拒绝场景，报告写入 `agent-eval/target/edd/security-chapter-24.json`，
断言 `mode=deterministic`、`modelCallAttempts=0`、所有任务通过且报告不含 Prompt 原文或凭据格式。
`JdbcSecurityViolationSinkTest` 在真实 Docker `postgres:16-alpine` 中执行 V1-V3 Flyway 迁移并验证字段
往返，证明安全记录不是内存假数据。

## 第八篇 25：Deployment 运行边界与恢复门禁

### 【问题现象】容器可以启动，但编排层无法区分“进程活着”和“已经能接收 Agent 请求”

原有 Compose 只等待 PostgreSQL 健康，Agent 服务没有 HTTP readiness 探针；Flyway 尚未完成或数据库连接
失效时，负载均衡仍可能把请求发送到尚未可用的实例。Java 入口通过 shell 启动但没有 `exec`，SIGTERM
不会稳定地直接传递给 Spring Boot 进程。

### 【根因分析】运行生命周期、依赖就绪和容器资源没有形成同一份可验证契约

Spring Boot 默认没有暴露编排探针，Compose 也没有声明 Agent 的 CPU、内存和 PID 上限；生产镜像与本地
镜像的入口行为不一致。为兼容本地 Dockerfile，`.dockerignore` 还曾把整个 `agent-web/target` 目录送入
生产多阶段构建，导致上下文达到约 269 MB，构建门禁超时。

### 【解决方案/代码级实现】Actuator 探针、信号转发、资源上限和最小构建上下文

`agent-web` 引入 Spring Boot Actuator，精确暴露 `/actuator/health/liveness` 与
`/actuator/health/readiness`；readiness 同时检查 `readinessState` 和 `db`，配合 Flyway 完成后再接收流量。
`server.shutdown=graceful` 和 30 秒关闭阶段预算保证运行中的请求有明确排空窗口。两套 Compose 的 Agent
服务统一声明 `cpus: 2.0`、`mem_limit: 2g`、`pids_limit: 512`，并使用 curl readiness healthcheck。
两个 Dockerfile 的入口都改为 `exec java $JAVA_OPTS -jar app.jar`，运行时镜像安装 curl。`.dockerignore`
只重新包含本地模式所需的 `agent-web` JAR，生产构建上下文从实测约 269 MB 降到约 81 KB。

### 【证据】确定性部署 EDD、双 Compose 解析和真实镜像构建

`DeploymentEddTest` 精确检查两套 Compose、两个 Dockerfile、应用配置和恢复文档，写入
`agent-eval/target/edd/deployment-chapter-25.json`，报告 `modelCallAttempts=0`。两条
`docker compose ... config` 命令均返回 0；本地 Dockerfile 和生产多阶段 Dockerfile 均在 Docker Desktop
上真实构建成功。恢复流程记录在 `docs/deployment/backup-recovery.md`，包含 `pg_dump`、隔离库
`pg_restore`、Flyway 版本和只读校验命令，避免把破坏性恢复伪装成单元测试。
