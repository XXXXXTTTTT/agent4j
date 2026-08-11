# 真实多轮会话 EDD 设计

## 目标

为生产 Agent 的持久化会话增加一条可重复的真实模型验收场景，防止“首轮回答正常、
第二轮丢失上下文”的回归。该场景只验证已有 Conversation、Context Provider、Planner
和 Run 投影链路，不修改生产节点、数据库协议或前端行为。

## 精确验收协议

1. 使用已配置的 `AGENT_LLM_ENABLED=true` 和现有 OpenAI 兼容网关。
2. 在一个已导入的工作区创建新的 Conversation。
3. 第一轮提交包含两个明确事实的中文请求，等待 `GET /api/conversations/{conversationId}/turns`
   返回 `COMPLETED`，且 `assistantContent` 非空。
4. 第二轮使用同一 `conversationId`，要求先复述第一轮的两个事实，再完成一个基于事实的回答。
5. 第二轮也必须返回 `COMPLETED`、`assistantContent` 非空，并逐字包含两个事实的规范文本。
6. 重新读取全部轮次，必须得到两个升序 Turn，且两轮 `runId` 不为空且互不相同。

## 证据

脚本将不含密钥和完整 Prompt 的 JSON 写入被 Git 忽略的 `.agent4j/acceptance/evidence/`：

- `conversation-continuity.json`：Conversation、两轮 ID、状态、耗时和回答事实摘要；
- `conversation-continuity-turns.json`：最终的两个 Turn 投影；
- `conversation-continuity-run-*.json`：两轮 Run 的状态和 `planner.context*` 证据。

审计日志必须包含两轮的 `CONVERSATION_TURN_SUBMITTED`、`CONVERSATION_TURN_COMPLETED`、
Conversation/Turn/Run ID。脚本只打印 ID 和状态，不打印 API Key。

## 失败语义

- 任一轮进入 `FAILED`、超时或 `assistantContent` 为空，EDD 立即失败；
- 第二轮缺少任一第一轮事实，EDD 失败并保留完整响应文件；
- 任一轮 Run 不是 `COMPLETED`，EDD 失败；
- 不把 HTTP 200、存在 Trace 或模型请求次数当作成功，Conversation Turn 的持久化终态是权威结果。

## 非目标

本次不引入摘要模型、不扩大上下文窗口、不改变权限、不修改 `PlannerNode` 路由容错，
也不把真实 EDD 混入默认 `mvn test`。只有显式开启 `AGENT_LLM_ENABLED=true` 并运行验收脚本时
才访问外部模型。
