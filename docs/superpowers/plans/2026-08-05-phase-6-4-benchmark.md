# Phase 6.4 Benchmark Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在独立 `agent-eval` 模块中提供至少 50 个版本化业务 Task、可重复执行的 Benchmark Runner、`pass^k` 稳定性和 TTFT 报告。

**Architecture:** `agent-eval` 只依赖 `agent-core` 的基础类型和 Jackson，公开构造器注入的任务执行端口；JSONL 读取器负责精确协议校验，Runner 负责并发任务编排，Metrics 聚合器负责完整结果门禁和稳定排序，Writer 输出可复现 JSON。调用方可以用适配器绑定 `AgentRunService`，测试使用确定性执行器。

**Tech Stack:** Java 21 records、Jackson、JUnit 5、Maven multi-module。

---

### Task 1: 建立 agent-eval 模块与领域协议

**Files:**
- Modify: `pom.xml`
- Create: `agent-eval/pom.xml`
- Create: `agent-eval/src/main/java/com/agent/eval/BenchmarkTask.java`
- Create: `agent-eval/src/main/java/com/agent/eval/BenchmarkTaskSet.java`
- Create: `agent-eval/src/main/java/com/agent/eval/BenchmarkRunRequest.java`
- Create: `agent-eval/src/main/java/com/agent/eval/BenchmarkTaskExecutor.java`
- Create: `agent-eval/src/main/java/com/agent/eval/BenchmarkTaskResult.java`
- Test: `agent-eval/src/test/java/com/agent/eval/BenchmarkDomainTest.java`

- [ ] Step 1: 写 record 构造器和任务集校验的失败测试：空字段、重复 ID、少于 50 项、非正 `k`/并发度、首 Token 越界均拒绝。
- [ ] Step 2: 运行 `mvn -pl agent-eval -am "-Dtest=BenchmarkDomainTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，确认因模块和类型不存在而失败。
- [ ] Step 3: 添加模块依赖和最小不可变 record，实现 UTF-8 文本、唯一 ID、至少 50 项、时间顺序与失败原因校验。
- [ ] Step 4: 重跑同一命令，确认领域测试全部通过。
- [ ] Step 5: 提交 `feat(eval): define benchmark domain protocol`。

### Task 2: JSONL 任务集与 50+ 真实业务任务

**Files:**
- Create: `agent-eval/src/main/java/com/agent/eval/BenchmarkTaskSetReader.java`
- Create: `agent-eval/src/main/resources/benchmark/tasks.jsonl`
- Test: `agent-eval/src/test/java/com/agent/eval/BenchmarkTaskSetReaderTest.java`

- [ ] Step 1: 先写测试：资源文件至少 50 行，ID 唯一，字段精确，覆盖 `CODE`、`OPS`、`RAG`、`TRACE`、`WEB` 类别；未知字段、重复 ID、非法 JSON 和空行拒绝。
- [ ] Step 2: 运行 `mvn -pl agent-eval -am "-Dtest=BenchmarkTaskSetReaderTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，确认读取器不存在导致红灯。
- [ ] Step 3: 编写 50+ 条基于已落地 Phase 1–6 能力的任务 JSONL，读取器使用 Jackson 树校验后映射为 `BenchmarkTask`，禁止忽略未知字段。
- [ ] Step 4: 运行测试并检查精确计数、类别分布和 JSON 往返稳定性。
- [ ] Step 5: 提交 `feat(eval): add versioned benchmark task set`。

### Task 3: pass^k 与 TTFT 指标聚合

**Files:**
- Create: `agent-eval/src/main/java/com/agent/eval/BenchmarkReport.java`
- Create: `agent-eval/src/main/java/com/agent/eval/BenchmarkMetrics.java`
- Test: `agent-eval/src/test/java/com/agent/eval/BenchmarkMetricsTest.java`

- [ ] Step 1: 写 `k=1`、`k=3`、缺失重复、失败结果、TTFT 平均/p50/p95/max 和非法时间戳测试。
- [ ] Step 2: 运行 `mvn -pl agent-eval -am "-Dtest=BenchmarkMetricsTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，确认聚合 API 不存在而失败。
- [ ] Step 3: 实现按任务 ID 聚合的 `passK = all repetitions passed / task count`；缺少任一重复结果抛出异常；TTFT 只统计有首 Token 的结果，p50/p95 使用确定性的线性插值；结果按任务 ID和重复序号排序。
- [ ] Step 4: 运行指标测试，确认所有 double 有限且非负。
- [ ] Step 5: 提交 `feat(eval): calculate pass-k and TTFT metrics`。

### Task 4: Runner、并发隔离与报告 Writer

**Files:**
- Create: `agent-eval/src/main/java/com/agent/eval/BenchmarkRunner.java`
- Create: `agent-eval/src/main/java/com/agent/eval/BenchmarkReportWriter.java`
- Test: `agent-eval/src/test/java/com/agent/eval/BenchmarkRunnerTest.java`

- [ ] Step 1: 写确定性执行器测试：每项任务恰好执行 `k` 次，异常保留完整堆栈，最大并发不超过配置值，关闭后拒绝运行，结果稳定排序。
- [ ] Step 2: 运行 `mvn -pl agent-eval -am "-Dtest=BenchmarkRunnerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，确认 Runner 不存在而失败。
- [ ] Step 3: 用 Java 21 虚拟线程执行器实现有界并发；每次调用记录 `startedAt`、可选 `firstTokenAt` 和 `finishedAt`；异常转失败结果，不中断其他任务；Writer 使用 Jackson 输出稳定 UTF-8 JSON。
- [ ] Step 4: 运行 Runner 测试并验证报告可反序列化回 `BenchmarkReport`。
- [ ] Step 5: 提交 `feat(eval): run benchmark tasks and write reports`。

### Task 5: 真实 Agent 工作流适配与工程复盘

**Files:**
- Create: `agent-eval/src/main/java/com/agent/eval/AgentRunBenchmarkExecutor.java`
- Create: `agent-eval/src/test/java/com/agent/eval/AgentRunBenchmarkWorkflowTest.java`
- Modify: `docs/ENGINEERING_PITFALLS.md`

- [ ] Step 1: 写真实组合测试，使用现有 `AgentRunService`、`GraphRegistry` 和确定性 GraphFactory，执行任务集中的 CODE、OPS、RAG、TRACE 代表项，验证 Run 终态、首 Token 事件和报告 `pass^k`。
- [ ] Step 2: 运行 `mvn -pl agent-eval -am "-Dtest=AgentRunBenchmarkWorkflowTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，确认适配器接线缺口后最小修复。
- [ ] Step 3: 实现适配器：把 `BenchmarkTask` 映射为初始 `AgentState`，监听第一条模型/日志事件作为 `firstTokenAt`，从 `AgentRunService.get/history` 读取终态并按 `successCriteria` 端口返回通过结果；不解析自由文本猜测成功。
- [ ] Step 4: 更新 `docs/ENGINEERING_PITFALLS.md`，只记录任务数据契约、缺失重复门禁、TTFT 时钟边界、并发隔离和真实工作流已验证事实。
- [ ] Step 5: 提交 `test(eval): verify Agent benchmark workflow` 和 `docs(engineering): record Phase 6.4 benchmark pitfalls`。

### Task 6: 全量验收、提交与合并

**Files:**
- Modify: `docs/superpowers/plans/2026-08-05-phase-6-4-benchmark.md` only for checked steps if required.

- [ ] Step 1: 显式 JDK 21 执行 `mvn clean verify`，确认所有模块、真实 Docker/PTY/pgvector/Chromium 测试和 `agent-eval` 测试通过。
- [ ] Step 2: 在前端目录执行固定 Node 的 Vitest 和 `npm audit --audit-level=low`。
- [ ] Step 3: 执行 `git diff --check`、禁止依赖扫描、受管容器检查、工作树检查，维护复盘文档最终证据。
- [ ] Step 4: 使用代码审查和完成分支流程，自审所有新增公开类型、状态边界和报告 JSON；修复已证实问题后重新验证。
- [ ] Step 5: 将 `feat/phase-6-4-benchmark` fast-forward 合并到 `master`，只删除 Phase 6.4 worktree 和分支，保留 Phase 2–5 worktree。
