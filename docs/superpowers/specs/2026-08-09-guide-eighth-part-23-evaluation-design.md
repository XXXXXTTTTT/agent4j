# 第八篇 23：Evaluation 能力集、轨迹评分与 CI 门禁设计

## 1. 目标与边界

教程第 23 章要求 Agent 评测从“代码是否能跑”升级为“能力是否稳定、轨迹是否正确、成本和延迟是否可控”。
当前 `agent-eval` 已有 JSONL 任务集、并发 Runner、`pass^k`、TTFT 和 JSON 报告，但缺少能力集元数据、
工具/节点轨迹评分、token/cost 预算、失败分类和可供 CI 使用的统一门禁。

本里程碑只增强 `agent-eval` 的评测域，不修改生产图的路由、工具权限、持久化或前端协议。真实模型 EDD 仍然
只能通过显式 `AGENT_LLM_ENABLED=true` 开启；普通 Maven 测试不能访问外部模型或读取真实密钥。

## 2. 方案选择

### 方案 A：直接扩展 `BenchmarkTaskResult` 与 `BenchmarkReport`

把 token、费用、轨迹和失败分类加入现有 record。该方案改动少，但会改变报告 JSON 和所有执行器构造器，
让基础 Benchmark 与供应商遥测强耦合，拒绝采用。

### 方案 B：独立评测层叠加在已有 Benchmark 之上（采用）

新增不可变 `EvaluationCapability`、`EvaluationObservation`、`EvaluationSuite`、`EvaluationReport` 和
`EvaluationGate`。`BenchmarkReport` 继续表达执行结果；评测层通过 `(taskId, repetition)` 精确关联遥测，
对能力证据、轨迹顺序、token/cost 和失败分类做二次判定。已有 Runner、任务 JSONL 和报告读取代码保持兼容。

### 方案 C：引入外部评测框架

外部框架会带来网络、版本和模型供应商耦合，也无法表达本项目的节点/工具状态键所有权，违反去框架化边界，
不采用。

## 3. 公开评测协议

### 3.1 能力声明

```java
public record EvaluationCapability(
        String id,
        String chapter,
        List<String> requiredTrace,
        double minPassK,
        Duration maxTtftP95,
        BigDecimal maxCostUsd) {}
```

`requiredTrace` 是有序节点或工具名；轨迹必须按顺序出现，允许中间存在未声明事件。阈值均为包含边界，
费用以非负 USD `BigDecimal` 表示并固定四舍五入规则为 `HALF_UP`、4 位小数。

### 3.2 单次遥测

```java
public record EvaluationObservation(
        String taskId,
        int repetition,
        List<String> trace,
        long inputTokens,
        long outputTokens,
        BigDecimal costUsd,
        FailureCategory failureCategory,
        String failureDetail) {}
```

`(taskId, repetition)` 必须唯一；token 为非负整数，成功结果的 `failureCategory` 必须为 `NONE`，失败结果必须
提供非空分类和脱敏后的故障摘要。遥测不保存 Prompt、API Key、截图正文或完整模型回答。

`FailureCategory` 精确枚举为：`NONE`、`ROUTING`、`MODEL_TRANSPORT`、`TOOL_PROTOCOL`、`AUTHORIZATION`、
`TIMEOUT`、`BUDGET`、`PERSISTENCE`、`ASSERTION`、`UNKNOWN`。

### 3.3 聚合报告

`EvaluationSuite` 由任务集、能力声明和 `EvaluationGatePolicy` 组成，构造时拒绝重复能力 ID、未知任务 ID、
空轨迹名和反向阈值。`EvaluationReport` 包含：基础 `BenchmarkReport`、按能力聚合的通过数/失败数、轨迹
通过数、总 input/output tokens、总费用、失败分类计数、生成时间和门禁结果。所有集合不可变且稳定排序。

能力通过条件同时满足：基础任务通过、requiredTrace 是有序子序列、单次费用不超过能力预算、失败分类为 `NONE`。
能力 `passK` 是该能力任务在 k 次重复中全部通过的任务比例。

## 4. CI 门禁与失败语义

`EvaluationGatePolicy` 精确提供：

- `minPassK`：整个 Suite 的最低 `pass^k`；
- `maxTtftP95`：允许的 TTFT P95；
- `maxCostUsd`：整次 Suite 的费用上限；
- `maxFailureCount`：允许的非 `NONE` 失败观测数。

`EvaluationGate.evaluate(EvaluationReport)` 返回不可变 `EvaluationGateResult(passed, violations)`；每个 violation
包含精确 `metric`, `actual`, `limit`, `message`。`EvaluationGate.assertPassed` 失败时抛出
`EvaluationGateViolationException`，异常消息只包含指标和限制，不包含 Prompt、密钥或回答正文。CI 只需把
`assertPassed` 作为非零退出门禁，报告仍会在失败时写出。

## 5. 轨迹评分与 EDD 接入

`EvaluationScorer` 负责校验观察覆盖、计算能力指标和失败分类；requiredTrace 采用精确字符串比较，禁止大小写
或别名推断。现有 EDD 可以把节点事件、工具审计事件和模型调用摘要转换为 `EvaluationObservation`，从而在同一
报告中同时证明真实链路和质量阈值。确定性 EDD 继续证明协议与资源生命周期；Live EDD 继续显式调用真实端点，
两者在报告中通过 `mode=deterministic|live` 区分。

## 6. 测试门禁

- 域测试：能力/策略/观测 record 的空值、阈值、重复键、费用精度和脱敏错误。
- 评分测试：requiredTrace 顺序、缺事件、单次费用、失败分类和能力 pass^k。
- 门禁测试：每个阈值通过/违反、稳定 violation 顺序和敏感内容不进入异常消息。
- 报告测试：JSON 字段、稳定排序、不可变集合和读写往返。
- EDD：使用确定性 Runner 生成 CLI、GUI、RAG 和模型路由能力集报告；Live EDD 仅在显式开启时运行，报告必须
  含真实 transport、modelCallAttempts、失败分类和成本/延迟摘要。

## 7. 非目标

本里程碑不引入 Langfuse、LangSmith 或其他外部评测库，不实现自动提示词优化、人工标注 UI、在线生产采样和
供应商计费 API。成本由调用方以明确的 `costUsd` 传入；缺失遥测不会被猜测为零，而是拒绝生成通过报告。
