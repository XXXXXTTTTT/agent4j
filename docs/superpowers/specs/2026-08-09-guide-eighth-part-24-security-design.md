# 第八篇 24：Agent Security 设计

## 1. 目标与边界

本章在现有工作区成员权限、HITL 审批、CLI 工作区边界、Docker 隔离和工具 Schema 校验之上，补齐统一安全治理能力：

1. 对进入模型上下文的用户任务、项目知识和工具输出进行 Prompt Injection 标记。
2. 在工具 Schema 校验后、能力授权前执行可注入的工具参数策略。
3. 在工具结果返回 Agent 前递归脱敏敏感字段和值，保留结构而不暴露密钥或认证头。
4. 将 Prompt Injection、参数拒绝和权限拒绝作为结构化安全违规持久化到 PostgreSQL。
5. 以确定性红队任务集覆盖拒绝、标记、脱敏和审计行为；真实模型测试仍由 `AGENT_LLM_ENABLED=true` 显式开启。

本章不引入 LangChain4j、LangGraph4j 或新的身份认证框架，不改变已有 `ToolRegistry`、`WorkspaceAccessService`、`Checkpointer` 和会话数据的公开契约。

## 2. 核心类型

### 2.1 `agent-core/security`

| 类型 | 公开契约 |
| --- | --- |
| `SecurityDecision` | `ALLOW`、`FLAG`、`BLOCK`，大小写和枚举值固定 |
| `SecuritySeverity` | `LOW`、`MEDIUM`、`HIGH`、`CRITICAL` |
| `SecurityViolationType` | `PROMPT_INJECTION`、`TOOL_PARAMETER`、`AUTHORIZATION`、`OUTPUT_REDACTION` |
| `SecurityFinding` | `ruleId`、`severity`、`decision`、`summary`；summary 只能是脱敏规则摘要，不得包含输入正文 |
| `PromptSecurityAssessment` | `decision` 与不可变 `List<SecurityFinding>` |
| `PromptSecurityContext` | `UUID runId`、`String userId`、`String nodeName`、`String source` |
| `PromptInjectionDetector` | `PromptSecurityAssessment inspect(PromptSecurityContext context, String text)` |
| `DefaultPromptInjectionDetector` | 固定规则集，按规则 ID 顺序输出结果；规则只返回摘要，不返回匹配文本 |
| `ToolParameterDecision` | `SecurityDecision decision`、`ruleId`、`summary` |
| `ToolParameterPolicy` | `ToolParameterDecision inspect(ToolDefinition definition, ToolCall call, ToolInvocationContext context)` |
| `DefaultToolParameterPolicy` | 按精确工具名和 JSON Pointer 应用规则，并限制字符串长度、控制字符及敏感凭据格式 |
| `OutputRedactor` | `JsonNode redact(String toolName, JsonNode output)` |
| `DefaultOutputRedactor` | 递归复制 JSON；精确字段名 `apiKey`、`authorization`、`password`、`secret`、`token` 和匹配 `Bearer `/`sk-` 的字符串替换为 `[REDACTED]` |
| `SecurityViolation` | `violationId`、`runId`、`userId`、`nodeName`、可选 `toolName`、`type`、`severity`、`ruleId`、脱敏 `summary`、`occurredAt` |
| `SecurityViolationSink` | `void record(SecurityViolation violation)`；另有 `noop()` 工厂 |

所有 record 在构造器中执行空值、范围和脱敏约束；集合和 JSON 节点使用防御性复制。安全结果不会保存 Prompt、工具参数原文、完整工具输出或 API Key。

### 2.2 Prompt Injection 规则

`DefaultPromptInjectionDetector` 只使用明确的固定规则，不做模糊语义推断。规则 ID 及行为固定如下：

| ruleId | 检测目标 | 决定 | 严重级别 |
| --- | --- | --- | --- |
| `prompt.ignore-previous-instructions` | 要求忽略既有系统/项目规则 | `BLOCK` | `HIGH` |
| `prompt.reveal-hidden-instructions` | 要求输出系统 Prompt、内部规则或隐藏上下文 | `BLOCK` | `HIGH` |
| `prompt.exfiltrate-secrets` | 要求输出 API Key、Token、环境变量或凭据 | `BLOCK` | `CRITICAL` |
| `prompt.redirect-tool-authority` | 要求绕过审批、能力或工作区边界 | `BLOCK` | `CRITICAL` |
| `prompt.untrusted-content-instruction` | 外部内容以指令形式改变 Agent 行为 | `FLAG` | `MEDIUM` |

匹配只产生规则 ID 和固定中文摘要。`source` 仅用于审计字段，不参与大小写或结构猜测；`text` 为空时返回 `ALLOW`。

## 3. 工具执行数据流

`DefaultToolRegistry.execute` 的治理顺序固定为：

```text
ToolCall
  -> JSON Schema 校验
  -> ToolParameterPolicy.inspect
  -> ToolAuthorizer.authorize
  -> Handler 执行
  -> OutputRedactor.redact
  -> ToolResult + ToolAuditEvent
```

参数策略或授权结果不是 `ALLOW` 时：

- Handler 不得启动。
- 返回现有 `DENIED` 或 `APPROVAL_REQUIRED` 状态。
- 通过 `SecurityViolationSink` 写入 `TOOL_PARAMETER` 或 `AUTHORIZATION` 违规。
- `ToolAuditEvent` 继续只保存参数 SHA-256、状态和错误类型。

Handler 成功后先复制并脱敏输出，再构造 `ToolResult`；脱敏失败按 `OUTPUT_REDACTION` 违规处理并返回失败结果。原始输出只存在于 Handler 调用栈内，不进入 Trace、MDC、异常消息或数据库。

## 4. Planner 安全边界

`PlannerNode` 通过构造器注入 `PromptInjectionDetector` 和 `SecurityViolationSink`，保留现有构造器并使用默认实现。以下内容分别以精确 `source` 标识检查：

- 用户任务：`user.task`
- 项目知识：`project.knowledge`
- 工具输出：`tool.output`

检查发生在对应文本加入 `ModelRequest` 之前：

- `BLOCK`：不调用模型，写入 `planner.error` 与安全违规，返回 `FAILED_ROUTE`。
- `FLAG`：继续调用模型，同时发布只包含 `ruleId/severity/source` 的 Trace 摘要。
- `ALLOW`：不产生安全事件。

检测器异常不能静默放行：Planner 写入脱敏错误类型并以 `BLOCK` 处理，保证安全组件故障默认拒绝。

## 5. PostgreSQL 持久化

新增 Flyway 迁移 `V3__security_violations.sql`：

```sql
create table agent_security_violations (
    violation_id uuid primary key,
    run_id uuid not null,
    user_id varchar(128) not null,
    node_name varchar(128) not null,
    tool_name varchar(128),
    violation_type varchar(32) not null,
    severity varchar(16) not null,
    rule_id varchar(128) not null,
    summary varchar(512) not null,
    occurred_at timestamp(6) with time zone not null
);

create index idx_security_violations_run
    on agent_security_violations (run_id, occurred_at);
create index idx_security_violations_user
    on agent_security_violations (user_id, occurred_at);
```

`JdbcSecurityViolationSink` 使用现有 `JdbcClient` 和事务边界执行追加写入。查询接口仅在后续 Web 安全查询任务中增加；本章只保证生产链路可持久化，避免绕过现有工作区成员权限。

## 6. 红队任务集与 EDD

`agent-eval` 新增 `SecurityRedTeamEddTest`，使用确定性端口和固定任务集合覆盖：

1. 忽略前置规则、泄露隐藏 Prompt、绕过审批：必须 `BLOCK`。
2. 外部页面文本中的伪指令：必须 `FLAG` 且摘要不包含原文。
3. 工具参数包含控制字符、凭据格式或未声明 JSON Pointer：必须拒绝且不调用 Handler。
4. 缺少能力、未批准高风险工具：必须拒绝/等待审批并写入 `AUTHORIZATION` 违规。
5. 工具输出中的 `authorization`、`apiKey`、`token` 和 Bearer/`sk-` 值：结构保留，敏感值为 `[REDACTED]`。
6. 每个违规事件包含 `runId/userId/nodeName/type/severity/ruleId/summary`，不包含 Prompt、参数正文、完整输出或密钥。

EDD 报告写入 `agent-eval/target/edd/security-chapter-24.json`，报告模式为 `deterministic`，`modelCallAttempts=0`。真实红队模型 EDD 只能在显式 `AGENT_LLM_ENABLED=true` 下运行。

## 7. 错误处理与兼容性

- 安全策略拒绝复用现有 `ToolResultStatus.DENIED`、`APPROVAL_REQUIRED` 和 Planner 的 `FAILED_ROUTE`，不引入字符串推断。
- 违规写入失败必须记录日志并保留原始安全决定；不得因为审计存储故障而放行被拒绝的动作。
- 输出脱敏是单向复制操作，不修改 Handler 返回的原始 JSON 节点。
- 所有新增构造器通过现有默认构造器提供兼容路径，生产装配显式注入安全端口。
- 既有工作区、HITL、CLI、Docker 和工具审计测试必须保持通过。

## 8. 验收门禁

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -pl agent-core,agent-web,agent-eval -am test
mvn clean package '-DskipTests' '-Dfrontend.skip=true'
git diff --check
```

验收要求：Java 21 下 0 failures、0 errors；安全红队 EDD 通过；迁移脚本可被 Flyway 解析；无 Prompt、API Key 或工具输出原文进入持久化审计。
