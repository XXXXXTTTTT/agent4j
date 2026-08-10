# 会话审计与北京时间日志设计

## 问题

现有 `agent4j-current.log` 只承接框架、模型和节点运行日志。用户输入与 Agent 最终回答仅保存在 PostgreSQL 会话表中，没有业务事件写入日志。Logback 时间格式未声明时区，Docker JVM 使用 UTC，因此宿主机查看时间比北京时间慢八小时。

## 设计

- PostgreSQL 与 Checkpoint 继续保存 UTC `Instant`，保持跨时区一致性。
- Logback 控制台、应用文件、滚动文件显式使用 `Asia/Shanghai`，Docker 同时设置 `TZ=Asia/Shanghai` 与 `-Duser.timezone=Asia/Shanghai`。
- 普通日志继续写入 `logs/agent4j-current.log`。
- 会话业务事件额外写入 `logs/audit/agent4j-audit-current.log`，格式为一行一个 JSON，并按北京时间自然日归档 30 天。
- 审计事件覆盖会话创建、归档，以及轮次提交、Run 绑定、完成和失败。轮次事件包含用户、工作区、会话、轮次、Run、状态、用户输入、Agent 最终回答、错误和耗时。
- 会话事件同时进入普通日志，便于沿既有 `runId`、`traceId`、`nodeName` MDC 上下文排查。
- 文件审计是业务无侵入的旁路：写入失败记录事件标识和异常，但不改变已提交的会话或 Run 状态。PostgreSQL 中的会话正文仍可用于补偿。
- 并发重复终态通知通过原子 Run 门禁只投影一次，避免重复终态审计。
- HTTP 未处理异常必须使用 ERROR 级别记录方法、路径和完整异常对象，响应仍隐藏内部实现细节。

## 安全边界

审计代码不读取请求头，并对当前模型 API Key、数据库密码、OTLP Authorization 的精确配置值，以及正文中的 `Bearer`、`sk-` 和敏感键值格式统一替换为 `[REDACTED]`。工具参数继续只保存既有 SHA-256 摘要。脱敏后的用户与 Agent 对话正文按产品审计需求保存，因此生产部署仍必须限制审计目录读取权限。

## 验证

- 单元测试验证提交、启动、完成、失败事件和正文。
- JSON 序列化测试验证 `occurredAt` 为 `+08:00` 且精确到毫秒。
- 脱敏测试验证配置凭据和正文令牌不会进入 JSONL；故障测试验证审计不可用不改变业务终态。
- 并发测试验证同一个 Run 的重复终态通知只写一条终态审计。
- 异常处理测试验证 500 日志保留请求方法、路径和 Throwable。
- Docker Compose 黑盒请求验证宿主机应用日志和独立审计文件均真实落盘。
