# 真实 Command 与多 Agent EDD 验收证据

验证时间：2026-08-15（北京时间）

## 环境前置条件

- Maven 测试进程必须使用 Java 21。本机默认 Java 17 无法加载 class version 65。
- 真实网关配置来自本机 `.env`，API Key 未写入日志、报告或仓库。
- Chat Completions 精确路径为 `/v1/chat/completions`。

## 网关探针

`gpt-5.4-mini` 在复验时返回过 HTTP 500：

```text
type=upstream_error
message=服务暂时不可用，请稍后重试
```

同一路径、同一 API Key 下，已配置模型 `gpt-5.6-luna` 返回 HTTP 200，内容为 `EDD_OK`：

```text
model=gpt-5.6-luna
prompt_tokens=308
completion_tokens=6
```

## 真实模型 EDD

测试：

```text
CommandEngineEddTest
ProductionMultiAgentEddTest
```

将代码模型与降级模型临时设为 `gpt-5.6-luna` 后，两项测试 2/2 通过：

- 网关探针：HTTP 200，输入 308 tokens，输出 6 tokens。
- 并行研究子 Agent：两个真实模型调用均为 HTTP 200。
- 子 Agent 调用一：输入 407 tokens，输出 361 tokens。
- 子 Agent 调用二：输入 408 tokens，输出 341 tokens。

## 真实降级链 EDD

保留主代码模型 `gpt-5.4-mini`，仅将降级模型设为 `gpt-5.6-luna`，再次执行 `ProductionMultiAgentEddTest`：

- 测试 1/1 通过。
- 父 Run：`ba3b0acb-1962-4c17-8a77-a386a9acb6fe`。
- 子 Run：`effbc053-d0d3-45aa-915a-35db7ef7e24f`。
- 子 Run：`b3a07944-9df6-4e5d-897b-53f9d0c2389a`。
- 模型调用次数：3。
- 主模型出现一次 HTTP 500；失败分支由 `gpt-5.6-luna` 完成。
- 两个子 Run 均包含 `STARTED`、`NODE_STARTED`、`NODE_COMPLETED`、`COMPLETED`。
- 两份研究结果均读取到 `Calculator.java` 的 `return a + b` 和 `CalculatorTest.java` 的 `new Calculator().add(1, 2)`。
- 自动报告结论：`passed=true`。

自动报告：

```text
agent-web/target/edd/production-multi-agent-1786788711388.json
```

## EGG 本地拦截

`CommandEngineEggTest` 2/2 通过：

- `/context` 在本地 Dispatcher 完成，工作流桥接调用数为 0。
- 只有明确的工作流命令才进入工作流桥接端口。

本次证据来自真实 HTTP 请求和真实子 Agent 执行，不使用伪造模型成功响应。
