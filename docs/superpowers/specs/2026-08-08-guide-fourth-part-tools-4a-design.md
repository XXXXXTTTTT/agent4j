# 第四篇 4A：核心 Tool Registry 设计

## 1. 背景与目标

Agent4J 当前已有 AST、PTY、Docker 和 Playwright 能力，但这些能力尚未通过统一的工具协议进入 Agent。`agent-core/tool` 只有包说明，模型工具调用、参数校验、权限判断、审批决策、超时和审计仍由调用方自行处理，无法保证后续 MCP、Skills 和 CLI 能力遵守同一安全边界。

4A 只实现核心 Tool Registry 领域协议与确定性执行入口，不实现 MCP、Skills、低代码工具编排或前端工具管理。完成后，任意工具必须以强类型定义注册，通过 JSON 参数 Schema、角色能力、风险级别和审批端口后才能执行，并返回不可变结构化结果与完整异常堆栈。

## 2. 设计边界

- 使用 Java 21 record、Jackson `JsonNode` 和现有 `HarnessHookChain`；不引入 JSON Schema 第三方库、MCP SDK 或 Agent 框架。
- 工具名、调用 ID、Schema 字段、角色能力和状态值全部精确匹配，禁止大小写或别名回退。
- Registry 只负责定义、查找、校验、授权、执行和审计；具体 AST、终端、浏览器实现通过 `ToolHandler` 注入。
- 工具执行必须在 Java 21 虚拟线程中完成，并由定义中的 `Duration timeout` 限制；超时取消任务并返回明确失败结果。
- Registry 不读取或修改 `AgentState`；调用方通过显式 `ToolInvocationContext` 传递 runId、nodeName、userId、workspaceRoot 和角色能力。
- 现有 `NodeExecutionContext.callTool` 保持兼容。图节点接入 Registry 时，由适配器在节点上下文中调用 Registry，Registry 仍产生统一 ToolAuditEvent；Hook 失败遵循现有关键/非关键规则。

## 3. 公开领域协议

### 3.1 风险和状态枚举

`ToolRiskLevel` 精确包含 `LOW`、`MEDIUM`、`HIGH`。

`ToolResultStatus` 精确包含 `SUCCEEDED`、`DENIED`、`APPROVAL_REQUIRED`、`TIMED_OUT`、`FAILED`。

`ToolAuthorizationDecision` 精确包含 `ALLOWED`、`DENIED`、`APPROVAL_REQUIRED`；`ToolAuthorization` 为 `(ToolAuthorizationDecision decision, String reason)` record，`DENIED` 和 `APPROVAL_REQUIRED` 的 reason 必须非空。

### 3.2 工具定义

```java
public record ToolDefinition(
        String name,
        String description,
        JsonNode inputSchema,
        Set<RequiredCapability> requiredCapabilities,
        ToolRiskLevel riskLevel,
        Duration timeout,
        ToolHandler handler) {}
```

约束：

- `name` 使用非空 ASCII 标识符，格式为 `[a-z][a-z0-9_.-]{0,63}`；注册表中名称唯一。
- `description` 非空且不超过 4000 个 Unicode code point。
- `inputSchema` 必须是 JSON object，根 `type` 必须为 `object`，`properties` 和 `required` 只能使用对象和字符串数组；Schema 节点不得包含 `$ref`、远程 URI 或脚本表达式。构造器保存 deep copy，重写 accessor 返回新的 deep copy。
- `requiredCapabilities` 去重、冻结且每项非空；`riskLevel`、`timeout`、`handler` 不得为 null；timeout 必须大于 0 且不超过 10 分钟。

### 3.3 调用、上下文和结果

```java
public record ToolCall(String callId, String name, JsonNode arguments) {}

public record ToolInvocationContext(
        UUID runId,
        String nodeName,
        String userId,
        Path workspaceRoot,
        Set<RequiredCapability> grantedCapabilities,
        boolean approvalGranted) {}

public record ToolResult(
        String callId,
        String name,
        ToolResultStatus status,
        JsonNode output,
        String errorStack,
        long durationMs) {}
```

调用约束：

- `callId` 和工具名非空；arguments 必须是 JSON object，禁止 null、数组和文本。构造器保存 deep copy，重写 accessor 返回新的 deep copy。
- `workspaceRoot` 在构造时执行 `toAbsolutePath().normalize()`；工具自身仍必须对具体路径做 real path 越界检查。
- `ToolResult` 成功时 output 非 null 且 errorStack 为空；其余状态 output 为 JSON null，并分别通过 `ToolAuthorizationException`、`ToolApprovalRequiredException`、`ToolTimeoutException` 或实际执行异常写入完整非空堆栈；durationMs 不得为负数。output 同样在构造和 accessor 两端 deep copy。
- `ToolHandler` 签名为 `JsonNode execute(ToolCall call, ToolInvocationContext context) throws Exception`，返回值必须是 JSON object 或 JSON array，Registry 统一 deep copy 后冻结。

## 4. Registry 与治理端口

```java
public interface ToolRegistry extends AutoCloseable {
    void register(ToolDefinition definition);
    Optional<ToolDefinition> find(String name);
    List<ToolDefinition> list();
    ToolResult execute(ToolCall call, ToolInvocationContext context);
}
```

生产实现 `DefaultToolRegistry` 使用不可变快照和 `ConcurrentHashMap` 注册表；`list()` 始终按工具名自然顺序返回不可变列表：

1. 注册时校验定义、名称唯一和 Schema 结构；重复名称立即抛 `ToolRegistrationException`。
2. 执行时按精确名称查找；未知名称返回 `FAILED`，错误中包含 `ToolNotFoundException` 的完整堆栈。
3. 先由 `ToolSchemaValidator` 校验 arguments，再由 `ToolAuthorizer` 返回 `ToolAuthorization`。Schema 或授权失败不得调用 handler。
4. `APPROVAL_REQUIRED` 只返回结果，不等待或模拟人工批准；调用方获得批准后必须使用新的 `ToolInvocationContext.approvalGranted=true` 重试。
5. 通过 `Executors.newVirtualThreadPerTaskExecutor()` 提交 handler，使用 `Future.get(timeout)`；超时执行 `cancel(true)`，返回 `TIMED_OUT`，并在审计中记录取消结果。
6. 所有路径都调用 `ToolAuditSink.record(ToolAuditEvent)`；审计失败不能覆盖原始工具结果。失败工具已有原始异常时，把审计异常加为 suppressed 后再序列化堆栈；成功工具的审计异常只写 SLF4J 错误日志并保持 `SUCCEEDED`。不得记录 arguments 正文、Bearer、密钥或完整源码。

### 4.1 Schema 校验范围

4A 实现确定性子集：根 object、`properties`、`required`、`additionalProperties=false`、`type` (`object/string/integer/number/boolean/array`)、`items`、`enum`、`minLength`、`maxLength`、`minimum`、`maximum`。允许的注释关键字只有 `title` 和 `description`；未声明字段只在 `additionalProperties=false` 时拒绝；Schema 出现其余关键字时注册失败，不在运行时猜测语义。

```java
public interface ToolSchemaValidator {
    void validateSchema(JsonNode schema);
    void validateArguments(JsonNode schema, JsonNode arguments);
}
```

生产实现 `JacksonToolSchemaValidator` 在注册时递归校验 Schema，在调用时递归校验 arguments；全部错误使用包含精确 JSON Pointer 的 `ToolSchemaException`。

### 4.2 权限端口

```java
@FunctionalInterface
public interface ToolAuthorizer {
    ToolAuthorization authorize(
            ToolDefinition definition,
            ToolCall call,
            ToolInvocationContext context);
}
```

默认授权器要求 `requiredCapabilities` 是 `grantedCapabilities` 的子集；`HIGH` 风险工具在 `approvalGranted=false` 时返回 `APPROVAL_REQUIRED`。授权器不得通过工具名、参数文本或大小写推断权限。

### 4.3 审计事件

```java
public record ToolAuditEvent(
        UUID runId,
        String nodeName,
        String userId,
        String callId,
        String toolName,
        Optional<ToolRiskLevel> riskLevel,
        ToolResultStatus status,
        long durationMs,
        String argumentsSha256,
        String errorType,
        boolean cancellationRequested) {}

@FunctionalInterface
public interface ToolAuditSink {
    void record(ToolAuditEvent event);
}
```

审计只保存 arguments 的 SHA-256、工具名、风险级别、结果状态、耗时和错误类型。只有未知工具的 `FAILED/ToolNotFoundException` 事件允许 `riskLevel=Optional.empty()`，其余事件必须保存已注册定义的风险级别。缺少 `NodeExecutionContext` 时仍可审计；在图节点中使用时，适配器额外通过现有 Harness Hook 发布 `BEFORE_TOOL`、`AFTER_TOOL` 或 `FAILURE`。

## 5. 错误与资源语义

- 注册异常、Schema 异常、找不到工具、授权异常和执行异常使用独立强类型异常，全部保留 cause。
- handler 抛出的 checked/unchecked exception 都转换为 `FAILED`，错误字段通过 `StringWriter/PrintWriter` 保存完整堆栈，不只保存 message。
- timeout 结果中的错误堆栈必须包含 `ToolTimeoutException`；审计事件记录 `Future.cancel(true)` 的布尔结果。handler 在中断后仍运行时，Registry 不等待第二次超时，也不伪造成功。
- Registry 实现 `AutoCloseable`，关闭时停止虚拟线程 Executor；关闭后 register/execute 立即抛 `IllegalStateException`。
- Registry 不自动重试工具。重试由上层图节点根据 `ToolResultStatus` 和执行预算决定，避免写操作重复执行。

## 6. 测试门禁

### 6.1 领域单元测试

- `ToolDefinitionTest`：名称、描述、Schema、能力集合、风险级别和 timeout 的边界；Schema 未支持关键字、重复名称和可变集合冻结。
- `ToolSchemaValidatorTest`：全部支持的 type/约束、缺失 required、未知字段、类型错误、非法数值和 arguments 非 object。
- `ToolRegistryTest`：精确查找、Schema 失败不调用 handler、权限拒绝不调用 handler、审批结果、成功结果 deep copy、完整异常堆栈和审计脱敏。
- `ToolRegistryTimeoutTest`：真实虚拟线程 handler 超时、interrupt、`TIMED_OUT` 结果和 close 后拒绝执行。

### 6.2 模块集成测试

- `ToolHarnessIntegrationTest`：在真实 `StateGraph` 节点中通过适配器调用 Registry，断言 Harness 事件顺序、runId/nodeName 一致、非关键审计 Hook 失败不改变工具结果、关键 Hook 失败阻止执行。
- `ToolRegistryConcurrencyTest`：并发注册同名工具只允许一个成功；并发执行之间不共享可变 arguments 或结果节点。

### 6.3 验收报告

4A 增加 `agent-eval/src/test/java/com/agent/eval/ToolRegistryEddTest.java`，使用确定性 handler 覆盖成功、Schema 拒绝、权限拒绝、审批、超时和异常六条路线，报告固定写入 `agent-eval/target/edd/tool-registry-edd.json`，每项字段精确为 `taskId/status/audited/durationMs/errorType/passed`。

## 7. 后续篇章接口

- 4B MCP 只能把远程工具转换为 `ToolDefinition` 与 `ToolHandler`，不得绕过 Registry。
- 4C Skills 只能提供只读元数据、触发条件和 Prompt 片段，触发后仍通过 Registry 执行工具。
- 第七篇 CLI/GUI 综合实战为 AST、PTY、Docker 和 Playwright 分别提供适配器；本篇不修改这些模块。

## 8. 提交与文档

规格提交使用：

```text
docs(tool): define core tool registry contract
```

实现完成后更新 `docs/ENGINEERING_PITFALLS.md`，记录 Schema 越界、审批等待误执行、超时取消不彻底和审计泄密等问题；所有日志、EDD 输出、`.env` 和 `target/` 继续由 `.gitignore` 排除。
