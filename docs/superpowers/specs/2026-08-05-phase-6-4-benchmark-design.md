# Phase 6.4 Benchmark 评测体系设计

## 目标与边界

Phase 6.4 实现 `AGENTS.md` 阶段六最后一项：建立包含至少 50 个版本化真实业务 Task 的
Benchmark 自动化评测管线，统计重复运行的 `pass^k` 稳定性和首字延迟 TTFT。

本阶段新增独立 `agent-eval` Maven 模块，不把评测状态写入 `agent-core` 的运行状态，也不
引入新的 Agent 编排框架、外部评测平台或真实模型密钥。任务执行器通过构造器注入，生产接线
可以调用现有 `AgentRunService`，单元测试使用确定性执行器。

## 模块边界

依赖方向为 `agent-eval -> agent-core`。`agent-eval` 不依赖 Spring、OpenTelemetry、数据库
或前端；Benchmark 结果先生成不可变内存报告，并通过 `BenchmarkReportWriter` 输出 UTF-8
JSON。真实运行的 Checkpoint、Trace 和模型配置仍由调用方提供。

公开类型位于 `com.agent.eval`：

- `BenchmarkTask`：`id`、`category`、`prompt`、`successCriteria`、`metadata`。
- `BenchmarkTaskSet`：不可变任务列表，要求 ID 唯一、数量至少 50。
- `BenchmarkRunRequest`：任务集、重复次数 `k`、并发度和超时。
- `BenchmarkTaskExecutor`：构造器注入的单次任务执行端口。
- `BenchmarkTaskResult`：任务 ID、重复序号、是否通过、开始时间、首 Token 时间、结束时间、
  失败原因。
- `BenchmarkReport`：所有结果、`passK`、平均/分位 TTFT、失败计数和生成时间。
- `BenchmarkRunner`：按任务顺序提交重复执行，收集结果并生成报告。
- `BenchmarkReportWriter`：将报告序列化为稳定 JSON。

## Task JSONL 协议

任务集文件固定为 `agent-eval/src/main/resources/benchmark/tasks.jsonl`，每行一个 JSON 对象，
字段精确为：

```json
{"id":"phase2.ast.extract-class","category":"CODE","prompt":"...","successCriteria":"...","metadata":{"phase":"2"}}
```

`id`、`category`、`prompt`、`successCriteria` 必须为非空字符串；`metadata` 必须是字符串键值
对象。读取器拒绝空行、重复 ID、未知字段、非法 JSON 和少于 50 项的任务集。任务内容覆盖
AST/Diff、PTY/Docker、Planner/Coder/Ops、Playwright/Reviewer、Checkpoint/HITL、RAG/Memory、
Trace/ModelRouter 和 Web 工作台等已落地能力；每项 success criteria 使用可观察结果描述，
不把模型文本相似度当作通过条件。

## 指标定义

### pass^k

对每个任务执行恰好 `k` 次。任务的 `pass^k` 指标为：所有重复结果 `passed == true` 的任务数
除以任务总数。报告同时保留每个任务的通过次数、失败次数和失败原因；任何缺少重复结果的任务
使整个报告失败，不允许静默按缺失结果计算。

### TTFT

`firstTokenAt - startedAt`，以纳秒采样并以毫秒输出。没有首 Token 的失败执行不进入 TTFT
分布，但保留失败结果；报告提供 `count`、`averageMs`、`p50Ms`、`p95Ms` 和 `maxMs`。时间
戳必须单调且首 Token 不得早于开始时间或晚于结束时间。

## 执行与错误语义

`BenchmarkRunner` 对每个任务的每次执行创建独立结果；任务执行异常转成带完整堆栈的失败结果，
不影响其他任务。并发度和 `k` 必须是正数，超时由执行器负责并在结果中标记失败。Runner 关闭
后拒绝新运行。报告聚合只接受完整结果集，并按任务 ID、重复序号稳定排序。

## 测试与验收

- JSONL 读取器测试：真实 50+ 任务、重复 ID、未知字段、空文本、非法 JSON 和少量任务拒绝。
- 指标测试：`k=1`、`k=3` 的通过率、缺失重复、TTFT 平均值和 p50/p95 插值、非法时间戳。
- Runner 测试：任务隔离、异常堆栈保留、超时、并发上限、稳定排序和重复运行。
- 真实工作流测试：使用现有图执行端口注入确定性执行器，至少运行任务集中的代表性 CODE、
  OPS、RAG 和 TRACE 任务，并验证报告 JSON 可往返。
- 完整验收固定使用 JDK 21、`mvn clean verify`、前端 Vitest、`npm audit`、禁止依赖扫描和
  Docker 资源清理；结果维护到 `docs/ENGINEERING_PITFALLS.md`。

## 非目标

本阶段不实现模型质量语义裁判、外部数据集同步、在线评测服务、Web UI、Langfuse 查询、
自动修改代码或 Benchmark 结果数据库。后续可在不改变领域协议的情况下增加持久化和可视化。
