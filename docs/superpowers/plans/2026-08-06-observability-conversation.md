# Agent4J 可观测性与问答闭环实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立可审计日志与实时过程事件，并让纯问答绕过代码链、代码失败停止级联。

**Architecture:** Core 负责 MDC、LLM 诊断、图进度和意图分流；Web 负责 Logback、SSE 和前端事件渲染；Sandbox 提供有界 Prompt 快照。现有 WebSocket/Checkpoint 协议继续兼容。

**Tech Stack:** Java 21、Spring RestClient、Apache HttpClient 5、SLF4J/Logback、Reactor WebFlux SSE、React/TypeScript、JUnit 5、Vitest。

---

### Task 1: Core 依赖与 MDC 上下文

**Files:** `agent-core/pom.xml`, `NodeExecutionContext.java`, `StateGraph.java`

- [ ] 添加 `slf4j-api` 依赖。
- [ ] 先写测试验证节点执行期间 `runId`、`traceId`、`nodeName` 可读且执行后清理。
- [ ] 为 `NodeExecutionContext` 增加进度发布 ThreadLocal 和 `progress(String)`，保持两参数 record 构造兼容。
- [ ] 在 `StateGraph` 执行节点时绑定 MDC 与进度回调。

### Task 2: Graph Progress 与 Trace Event

**Files:** `GraphExecutionListener.java`, `TraceEvent.java`, `TraceEventType.java`, `AgentRunService.java`

- [ ] 先写 `NODE_PROGRESS` 序列化和 StateGraph 实时回调测试。
- [ ] 添加默认 `onNodeProgress`，避免破坏现有监听器。
- [ ] AgentRunService 将进度发布为强类型 Trace 事件并保存当前 Checkpoint 版本。

### Task 3: LLM HTTP 超时、MDC 与审计日志

**Files:** `agent-core/pom.xml`, `LlmClient.java`, `agent-web/pom.xml`, `ModelGatewayConfiguration.java`, `logback-spring.xml`

- [ ] 先写 503、SocketTimeout、MDC 和观测字段测试。
- [ ] 使用 HttpClient 5 配置 5 秒连接、45 秒响应读取超时。
- [ ] 记录 URL、模型、Token、HTTP 状态和耗时；超时/503 输出 WARN 并保留异常。
- [ ] 加入控制台和 30 天滚动文件 Logback 配置。

### Task 4: Prompt 有界快照与节点过程摘要

**Files:** `WorkspaceSnapshotService.java`, `WorkspaceSnapshotServiceTest.java`, `CoderNode.java`, `OpsNode.java`, `PlannerNode.java`, `ReviewerNode.java`

- [ ] 先写大仓库 Prompt 快照不抛异常的测试。
- [ ] 实现 `captureForPrompt` 有界跳过，严格 `capture` 保持原语义。
- [ ] 在节点关键动作调用 `NodeExecutionContext.progress`，PTY 日志继续走 RunLogPublisher。

### Task 5: Planner 问答分流与生产图错误路由

**Files:** `PlannerNode.java`, `ProductionGraphConfiguration.java`, `AgentRunService.java`

- [ ] 先写“你是什么模型”直达 `final_response`、代码任务进入 Coder、Coder 失败不进入 Ops 的测试。
- [ ] 实现分层意图识别的 `chat`/`agent` 路由与 `final_response`，问答使用 QUICK_CLASSIFICATION，代码保持 CODE 规划；未命中快路径时再由模型做语义分流。
- [ ] 增加生产图条件边和错误终态判定。

### Task 6: SSE 与前端过程体验

**Files:** `RunTraceController.java`, `TraceEvent` 前端契约、`TraceTimeline.tsx`, `AgentConversation.tsx`

- [ ] 先写 SSE 快照/事件帧和 `NODE_PROGRESS` 解码测试。
- [ ] 提供 `/api/runs/{runId}/events`，前端展示实时动作摘要和 `final_response`。
- [ ] 保持 WebSocket/终端回归测试通过。

### Task 7: 验证、文档与提交

- [ ] 运行受影响模块单元测试。
- [ ] 执行 `mvn clean package -DskipTests` 并检查 Boot Jar。
- [ ] 使用 Compose + 修复后的模型端点做真实问答和代码任务黑盒验证。
- [ ] 更新 `README.md` 与 `docs/ENGINEERING_PITFALLS.md`，检查 `.gitignore`、`git diff --check`。
- [ ] 提交 `feat(observability): ...` 和必要的原子修复提交。
