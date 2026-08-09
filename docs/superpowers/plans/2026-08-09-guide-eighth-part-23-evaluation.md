# 第八篇 23：Evaluation 能力集与 CI 门禁实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变现有 Benchmark Runner、生产图和真实模型开关的前提下，增加能力集声明、轨迹/遥测评分、成本与延迟预算、失败分类和可用于 CI 的评测门禁。

**Architecture:** `BenchmarkReport` 继续作为基础执行结果；新的 `EvaluationSuite` 通过 `(taskId, repetition)` 精确关联不可变 `EvaluationObservation`，由 `EvaluationScorer` 生成 `EvaluationReport`，最后由 `EvaluationGate` 计算稳定、可审计的 violations。所有外部调用仍由调用方显式提供遥测和 `AGENT_LLM_ENABLED` 控制。

**Tech Stack:** Java 21 records、`BigDecimal`、JUnit 5、AssertJ、Jackson、现有 Maven `agent-eval` 模块。

**Progress:** Task 1-5 complete; Java 21 full verification, documentation commit, fast-forward merge, and post-merge Evaluation regression all passed.

---

### Task 1: 评测领域值对象与失败分类

**Files:**
- Create: `agent-eval/src/main/java/com/agent/eval/FailureCategory.java`
- Create: `agent-eval/src/main/java/com/agent/eval/EvaluationCapability.java`
- Create: `agent-eval/src/main/java/com/agent/eval/EvaluationGatePolicy.java`
- Test: `agent-eval/src/test/java/com/agent/eval/EvaluationDomainTest.java`

- [x] **Step 1: Write the failing tests**

测试必须覆盖：能力 ID/chapter/requiredTrace 的空值拒绝；`minPassK` 只能为有限的 0..1；两个时间和费用预算必须为正；`BigDecimal` 费用按 4 位小数 `HALF_UP` 归一化；策略拒绝负 `maxFailureCount`、零或负预算；所有返回集合不可变。

```java
@Test
void normalizesCapabilityCostAndDefensivelyCopiesTrace() {
    List<String> trace = new ArrayList<>(List.of("planner", "tool"));
    EvaluationCapability capability = new EvaluationCapability(
            "cli-repair", "7A", trace, 0.8,
            Duration.ofSeconds(2), new BigDecimal("1.23456"));
    trace.add("reviewer");
    assertThat(capability.requiredTrace()).containsExactly("planner", "tool");
    assertThat(capability.maxCostUsd()).isEqualByComparingTo("1.2346");
    assertThatThrownBy(() -> capability.requiredTrace().add("x"))
            .isInstanceOf(UnsupportedOperationException.class);
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -pl agent-eval -Dtest=EvaluationDomainTest test`

Expected: FAIL because the three domain types do not exist.

- [x] **Step 3: Write minimal implementation**

`FailureCategory` 只声明规格中的十个精确枚举值。两个 record 使用 `List.copyOf`、`Objects.requireNonNull` 和明确范围校验；费用通过 `setScale(4, RoundingMode.HALF_UP)` 保存，禁止 `null`、NaN 或负值。策略提供 `minPassK/maxTtftP95/maxCostUsd/maxFailureCount` 四个组件和同样的校验。

- [x] **Step 4: Run test to verify it passes**

Run: `mvn -pl agent-eval -Dtest=EvaluationDomainTest test`

Expected: PASS with all domain validation assertions green.

- [x] **Step 5: Commit**

```powershell
git add agent-eval/src/main/java/com/agent/eval/FailureCategory.java agent-eval/src/main/java/com/agent/eval/EvaluationCapability.java agent-eval/src/main/java/com/agent/eval/EvaluationGatePolicy.java agent-eval/src/test/java/com/agent/eval/EvaluationDomainTest.java
git commit -m "feat(eval): add evaluation capability policy domain"
```

### Task 2: 单次遥测、轨迹评分与严格关联

**Files:**
- Create: `agent-eval/src/main/java/com/agent/eval/EvaluationObservation.java`
- Create: `agent-eval/src/main/java/com/agent/eval/EvaluationTraceScorer.java`
- Test: `agent-eval/src/test/java/com/agent/eval/EvaluationObservationTest.java`
- Test: `agent-eval/src/test/java/com/agent/eval/EvaluationTraceScorerTest.java`

- [x] **Step 1: Write the failing tests**

测试先写以下行为：成功观察必须 `FailureCategory.NONE` 且失败摘要为空；失败观察必须有非空分类和脱敏摘要；token 非负、费用按 4 位归一化；同一任务重复键被拒绝；轨迹 `planner,tool,reviewer` 对 `planner,reviewer` 为真，对 `tool,planner` 为假，比较区分大小写。

```java
@Test
void scoresRequiredTraceAsOrderedCaseSensitiveSubsequence() {
    assertThat(EvaluationTraceScorer.containsInOrder(
            List.of("planner", "tool", "reviewer"),
            List.of("planner", "reviewer"))).isTrue();
    assertThat(EvaluationTraceScorer.containsInOrder(
            List.of("planner", "tool"), List.of("Planner"))).isFalse();
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -pl agent-eval -Dtest=EvaluationObservationTest,EvaluationTraceScorerTest test`

Expected: FAIL because the observation and scorer types do not exist.

- [x] **Step 3: Write minimal implementation**

`EvaluationObservation` 为不可变 record，组件为 `taskId/repetition/trace/inputTokens/outputTokens/costUsd/failureCategory/failureDetail`；构造器执行上述精确校验并拒绝敏感字段模式 `sk-`、`Bearer ` 和换行 Prompt 正文。`EvaluationTraceScorer.containsInOrder` 使用 `Objects.equals` 顺序扫描，不做大小写、格式或别名处理。

- [x] **Step 4: Run test to verify it passes**

Run: `mvn -pl agent-eval -Dtest=EvaluationObservationTest,EvaluationTraceScorerTest test`

Expected: PASS.

- [x] **Step 5: Commit**

```powershell
git add agent-eval/src/main/java/com/agent/eval/EvaluationObservation.java agent-eval/src/main/java/com/agent/eval/EvaluationTraceScorer.java agent-eval/src/test/java/com/agent/eval/EvaluationObservationTest.java agent-eval/src/test/java/com/agent/eval/EvaluationTraceScorerTest.java
git commit -m "feat(eval): add telemetry and ordered trace scoring"
```

### Task 3: 能力聚合报告

**Files:**
- Create: `agent-eval/src/main/java/com/agent/eval/EvaluationSuite.java`
- Create: `agent-eval/src/main/java/com/agent/eval/EvaluationReport.java`
- Create: `agent-eval/src/main/java/com/agent/eval/EvaluationScorer.java`
- Test: `agent-eval/src/test/java/com/agent/eval/EvaluationScorerTest.java`

- [x] **Step 1: Write the failing tests**

固定 50 项任务、两项能力和完整重复结果，断言：缺失观测、未知任务、重复 `(taskId,repetition)` 均失败；能力报告按能力 ID 排序；轨迹缺失、基础任务失败和单次费用超预算会使对应能力执行失败；报告汇总 input/output tokens、总费用、失败分类计数和 TTFT；所有列表和 map 不可变。

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -pl agent-eval -Dtest=EvaluationScorerTest test`

Expected: FAIL because suite/report/scorer types do not exist.

- [x] **Step 3: Write minimal implementation**

`EvaluationSuite(String id, BenchmarkTaskSet taskSet, Map<String, String> taskCapabilities, List<EvaluationCapability> capabilities, EvaluationGatePolicy policy)` 保存不可变任务到能力的精确映射；每个任务只能属于一个能力且不能遗漏。`EvaluationScorer.score` 先调用 `BenchmarkMetrics.calculate`，再验证每个结果都有 observation，计算能力级 passK、轨迹通过数、token/cost 和 `EnumMap<FailureCategory,Integer>`。输出 record 的排序规则固定为能力 ID、任务 ID、repetition。

- [x] **Step 4: Run test to verify it passes**

Run: `mvn -pl agent-eval -Dtest=EvaluationScorerTest test`

Expected: PASS.

- [x] **Step 5: Commit**

```powershell
git add agent-eval/src/main/java/com/agent/eval/EvaluationSuite.java agent-eval/src/main/java/com/agent/eval/EvaluationReport.java agent-eval/src/main/java/com/agent/eval/EvaluationScorer.java agent-eval/src/test/java/com/agent/eval/EvaluationScorerTest.java
git commit -m "feat(eval): aggregate capability evaluation reports"
```

### Task 4: CI 门禁、报告写入与 EDD 接入

**Files:**
- Create: `agent-eval/src/main/java/com/agent/eval/EvaluationGate.java`
- Create: `agent-eval/src/main/java/com/agent/eval/EvaluationGateResult.java`
- Create: `agent-eval/src/main/java/com/agent/eval/EvaluationGateViolationException.java`
- Create: `agent-eval/src/main/java/com/agent/eval/EvaluationMode.java`
- Modify: `agent-eval/src/main/java/com/agent/eval/BenchmarkReportWriter.java`
- Create: `agent-eval/src/test/java/com/agent/eval/EvaluationGateTest.java`
- Create: `agent-eval/src/test/java/com/agent/eval/EvaluationReportWriterTest.java`
- Create: `agent-eval/src/test/java/com/agent/eval/EvaluationEddTest.java`

- [x] **Step 1: Write the failing tests**

测试断言每个 policy 阈值分别通过/违反；violations 按固定指标顺序 `passK/ttftP95/costUsd/failureCount` 输出；异常消息不包含 Prompt、`Bearer ` 或 `sk-`；报告 JSON 可写入并读回，字段包含 `suiteId/mode/capabilities/metrics/tokens/cost/failures/gate`。

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -pl agent-eval -Dtest=EvaluationGateTest,EvaluationReportWriterTest,EvaluationEddTest test`

Expected: FAIL because the gate, result and EDD types do not exist.

- [x] **Step 3: Write minimal implementation**

`EvaluationReport` 保存 `EvaluationGatePolicy`，能力指标保存对应 `minPassK/maxTtftP95`。`EvaluationGate.evaluate` 使用这些聚合值生成不可变 `EvaluationGateResult`；`assertPassed` 在失败时抛出带 violation 列表的强类型异常。`BenchmarkReportWriter` 增加接收 `EvaluationMode`、`modelCallAttempts`、报告和门禁结果的重载，复用现有 Jackson 模块并保持 UTF-8、ISO-8601 时间和稳定排序。`EvaluationEddTest` 使用确定性 executor 生成 CLI、GUI、RAG 三个能力，写入 `target/edd/evaluation-chapter-23.json`，断言报告通过且 `modelCallAttempts=0`；Live EDD 不在普通测试中开启。

- [x] **Step 4: Run test to verify it passes**

Run: `mvn -pl agent-eval -Dtest=EvaluationGateTest,EvaluationReportWriterTest,EvaluationEddTest test`

Expected: PASS and report exists under `agent-eval/target/edd/`.

- [x] **Step 5: Commit**

```powershell
git add agent-eval/src/main/java/com/agent/eval/EvaluationGate.java agent-eval/src/main/java/com/agent/eval/EvaluationGateResult.java agent-eval/src/main/java/com/agent/eval/EvaluationGateViolationException.java agent-eval/src/main/java/com/agent/eval/EvaluationMode.java agent-eval/src/main/java/com/agent/eval/BenchmarkReportWriter.java agent-eval/src/test/java/com/agent/eval/EvaluationGateTest.java agent-eval/src/test/java/com/agent/eval/EvaluationReportWriterTest.java agent-eval/src/test/java/com/agent/eval/EvaluationEddTest.java
git commit -m "feat(eval): enforce evaluation ci gate"
```

### Task 5: 文档、完整门禁与合并

**Files:**
- Modify: `docs/ENGINEERING_PITFALLS.md`
- Modify: `docs/superpowers/plans/2026-08-09-guide-eighth-part-23-evaluation.md`

- [x] **Step 1: Append the chapter 23 pitfall**

记录“单元测试全绿但没有质量阈值”的现象、把基础执行结果和评测遥测耦合的根因、独立 Evaluation 层和显式 Live EDD 的解决方案，引用 `EvaluationScorer`、`EvaluationGate` 和 `EvaluationEddTest`。

- [x] **Step 2: Run focused and full verification**

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -pl agent-core,agent-web,agent-eval -am test
mvn clean package -DskipTests -Dfrontend.skip=true
git diff --check
```

Expected: Java 测试 0 failures/0 errors；普通 EDD 只按明确 assumption 跳过外部 Live 场景；打包为 `BUILD SUCCESS`。

- [x] **Step 3: Commit documentation**

```powershell
git add docs/ENGINEERING_PITFALLS.md docs/superpowers/plans/2026-08-09-guide-eighth-part-23-evaluation.md
git commit -m "docs(eval): record chapter 23 evaluation pitfalls"
```

- [x] **Step 4: Merge and clean up**

确认 feature 工作树 clean 后，在 `D:\agent4j` 执行 `git merge --ff-only feat/guide-eighth-part-23-evaluation`，在合并后的 master 重跑 Evaluation EDD 和关键门禁；成功后删除本次专属 worktree、prune 元数据和已合并分支，最终 `git status --short --branch` 只显示 clean `master`。
