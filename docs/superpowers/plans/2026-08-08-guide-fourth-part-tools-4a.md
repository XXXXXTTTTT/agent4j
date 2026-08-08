# 第四篇 4A：核心 Tool Registry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立唯一、强类型、可审计的工具注册与执行入口，使工具在执行前统一经过 Schema、能力、风险和审批校验，并在虚拟线程中受超时约束。

**Architecture:** `agent-core.tool` 保存不可变工具协议、确定性 JSON Schema 子集验证、默认授权器、Registry 和 Harness 适配器；具体 AST、终端、浏览器工具暂不迁移。Registry 返回结构化 `ToolResult`，Harness 适配器只负责把结果映射到现有 `BEFORE_TOOL/AFTER_TOOL/FAILURE` 生命周期。

**Tech Stack:** Java 21 records 与虚拟线程、Jackson `JsonNode`、现有 `RequiredCapability`、`NodeExecutionContext` 与 `HarnessHookChain`、JUnit 5、AssertJ。

---

### Task 1: 工具领域枚举、授权与异常协议

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/tool/ToolRiskLevel.java`
- Create: `agent-core/src/main/java/com/agent/core/tool/ToolResultStatus.java`
- Create: `agent-core/src/main/java/com/agent/core/tool/ToolAuthorizationDecision.java`
- Create: `agent-core/src/main/java/com/agent/core/tool/ToolAuthorization.java`
- Create: `agent-core/src/main/java/com/agent/core/tool/ToolException.java`
- Create: `agent-core/src/main/java/com/agent/core/tool/ToolRegistrationException.java`
- Create: `agent-core/src/main/java/com/agent/core/tool/ToolNotFoundException.java`
- Create: `agent-core/src/main/java/com/agent/core/tool/ToolSchemaException.java`
- Create: `agent-core/src/main/java/com/agent/core/tool/ToolAuthorizationException.java`
- Create: `agent-core/src/main/java/com/agent/core/tool/ToolApprovalRequiredException.java`
- Create: `agent-core/src/main/java/com/agent/core/tool/ToolTimeoutException.java`
- Create: `agent-core/src/test/java/com/agent/core/tool/ToolAuthorizationTest.java`

- [x] **Step 1: 写失败领域测试**：断言三个枚举的精确常量集合；`ToolAuthorization(ALLOWED, "")` 合法，`DENIED/APPROVAL_REQUIRED` 只接受非空 reason；所有异常保留精确字段和 cause，`ToolSchemaException` 保存非空 JSON Pointer。
- [x] **Step 2: 运行红灯**：

  ```powershell
  $env:JAVA_HOME='C:\Program Files\Java\jdk-21'
  $env:Path="$env:JAVA_HOME\bin;$env:Path"
  mvn -pl agent-core -am `
    '-Dtest=ToolAuthorizationTest' `
    '-Dsurefire.failIfNoSpecifiedTests=false' test
  ```

  预期因工具领域类型不存在而测试编译失败。
- [x] **Step 3: 写最小实现**：三个 enum 只声明规格中的精确值；授权 record 冻结 decision/reason；`ToolException` 继承 `RuntimeException`，具体异常只增加规格要求的不可变字段，不吞 cause。
- [x] **Step 4: 运行绿灯**：重复指定测试，预期 `ToolAuthorizationTest` 全部通过。
- [x] **Step 5: 提交**：

  ```text
  feat(tool): define authorization protocol
  ```

### Task 2: 不可变定义、调用、上下文与结果

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/tool/ToolHandler.java`
- Create: `agent-core/src/main/java/com/agent/core/tool/ToolDefinition.java`
- Create: `agent-core/src/main/java/com/agent/core/tool/ToolCall.java`
- Create: `agent-core/src/main/java/com/agent/core/tool/ToolInvocationContext.java`
- Create: `agent-core/src/main/java/com/agent/core/tool/ToolResult.java`
- Create: `agent-core/src/test/java/com/agent/core/tool/ToolDefinitionTest.java`

- [x] **Step 1: 写失败定义测试**：验证工具名正则 `[a-z][a-z0-9_.-]{0,63}`、描述 code point 上限 4000、timeout 范围 `(0, 10m]`、非空 handler、`Set<RequiredCapability>` 冻结和 null 元素拒绝。
- [x] **Step 2: 写失败不可变测试**：构造后修改原 `ObjectNode`，定义 Schema、调用 arguments 和结果 output 不变；修改 accessor 返回节点也不影响内部值。`ToolCall.arguments` 只允许 object；成功结果要求 object/array output 与空 errorStack，其余状态要求 JSON null output 与完整非空 errorStack。
- [x] **Step 3: 写失败上下文测试**：精确校验 runId/nodeName/userId；workspaceRoot 转绝对规范路径；能力集合冻结且不含 null；不要求目录已经存在。
- [x] **Step 4: 运行红灯**：

  ```powershell
  mvn -pl agent-core -am `
    '-Dtest=ToolDefinitionTest' `
    '-Dsurefire.failIfNoSpecifiedTests=false' test
  ```

- [x] **Step 5: 写最小实现**：records 在紧凑构造器保存 `deepCopy()`，并重写对应 accessor 返回 `deepCopy()`；`ToolHandler` 精确声明：

  ```java
  @FunctionalInterface
  public interface ToolHandler {
      JsonNode execute(ToolCall call, ToolInvocationContext context) throws Exception;
  }
  ```

- [x] **Step 6: 运行绿灯与核心回归**：重复指定测试，再运行 `mvn -pl agent-core -am test`。
- [x] **Step 7: 提交**：

  ```text
  feat(tool): define immutable tool contracts
  ```

### Task 3: 确定性 JSON Schema 子集

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/tool/ToolSchemaValidator.java`
- Create: `agent-core/src/main/java/com/agent/core/tool/JacksonToolSchemaValidator.java`
- Create: `agent-core/src/test/java/com/agent/core/tool/ToolSchemaValidatorTest.java`

- [x] **Step 1: 写失败 Schema 定义测试**：根必须为 `type=object`；递归支持 `properties/required/additionalProperties/type/items/enum/minLength/maxLength/minimum/maximum/title/description`；`required` 重复、属性名空白、类型未知、约束类型错误、min 大于 max、array 缺 items 和任何未支持关键字均以精确 JSON Pointer 失败。
- [x] **Step 2: 写失败参数测试**：覆盖 object/string/integer/number/boolean/array、嵌套 required、enum、字符串长度、数值上下限、array items；`additionalProperties=false` 拒绝未知字段，未声明或 true 时保留未知字段；NaN/Infinity 不接受。
- [x] **Step 3: 运行红灯**：

  ```powershell
  mvn -pl agent-core -am `
    '-Dtest=ToolSchemaValidatorTest' `
    '-Dsurefire.failIfNoSpecifiedTests=false' test
  ```

- [x] **Step 4: 写最小接口和实现**：

  ```java
  public interface ToolSchemaValidator {
      void validateSchema(JsonNode schema);
      void validateArguments(JsonNode schema, JsonNode arguments);
  }
  ```

  `JacksonToolSchemaValidator` 使用递归方法携带 JSON Pointer；数字使用 `BigDecimal` 比较；required 用 `LinkedHashSet` 检测重复；关键字按当前 Schema 层级白名单校验，不忽略未知关键字。
- [x] **Step 5: 运行绿灯与重复测试**：指定测试连续运行两次，再运行 `mvn -pl agent-core -am test`。
- [x] **Step 6: 提交**：

  ```text
  feat(tool): validate deterministic json schemas
  ```

### Task 4: 默认授权器与脱敏审计协议

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/tool/ToolAuthorizer.java`
- Create: `agent-core/src/main/java/com/agent/core/tool/DefaultToolAuthorizer.java`
- Create: `agent-core/src/main/java/com/agent/core/tool/ToolAuditEvent.java`
- Create: `agent-core/src/main/java/com/agent/core/tool/ToolAuditSink.java`
- Create: `agent-core/src/test/java/com/agent/core/tool/ToolGovernanceTest.java`

- [x] **Step 1: 写失败授权测试**：能力不足返回 `DENIED` 并列出缺失的精确枚举名；能力满足时 LOW/MEDIUM 允许；HIGH 在未批准时返回 `APPROVAL_REQUIRED`、批准后允许；不得读取工具名或参数正文决定权限。
- [x] **Step 2: 写失败审计模型测试**：`ToolAuditEvent` 校验并冻结 runId/nodeName/userId/callId/toolName/risk/status/durationMs/argumentsSha256/errorType/cancellationRequested；SHA-256 只接受 64 位小写十六进制；SUCCEEDED 的 errorType 为空，其余状态必须非空；只有 `FAILED/ToolNotFoundException` 允许 `riskLevel=Optional.empty()`。
- [x] **Step 3: 运行红灯**：

  ```powershell
  mvn -pl agent-core -am `
    '-Dtest=ToolGovernanceTest' `
    '-Dsurefire.failIfNoSpecifiedTests=false' test
  ```

- [x] **Step 4: 写最小实现**：默认授权器先计算 `definition.requiredCapabilities - context.grantedCapabilities`，再判断 HIGH 审批；`ToolAuditSink.noop()` 只校验事件非 null，不产生外部副作用。
- [x] **Step 5: 运行绿灯**：重复指定测试。
- [x] **Step 6: 提交**：

  ```text
  feat(tool): authorize and audit tool calls
  ```

### Task 5: DefaultToolRegistry 执行闭环

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/tool/ToolRegistry.java`
- Create: `agent-core/src/main/java/com/agent/core/tool/DefaultToolRegistry.java`
- Create: `agent-core/src/test/java/com/agent/core/tool/ToolRegistryTest.java`
- Create: `agent-core/src/test/java/com/agent/core/tool/ToolRegistryTimeoutTest.java`

- [x] **Step 1: 写失败注册测试**：精确名称 find、自然顺序 list、重复注册只允许一次、Schema 在注册时校验、返回列表不可变；关闭后 register/execute 拒绝。
- [x] **Step 2: 写失败执行顺序测试**：记录 validator/authorizer/handler/audit 调用，固定顺序为查找→参数校验→授权→handler→审计；Schema、DENIED、APPROVAL_REQUIRED 均不得调用 handler；未知工具返回含 `ToolNotFoundException` 的 FAILED。
- [x] **Step 3: 写失败结果测试**：成功 deep copy handler output；checked/unchecked/null/标量输出均返回完整 FAILED 堆栈；argumentsSha256 基于递归按字段名排序、数组保持原顺序的规范 JSON 字节计算，相同对象字段顺序不同仍得到相同哈希；审计不得含原 arguments、源码或密钥。
- [x] **Step 4: 写失败超时测试**：真实阻塞 handler 观察 interrupt；超时返回 `TIMED_OUT`、`ToolTimeoutException` 堆栈、`cancellationRequested=true`，Registry 不等待 handler 的后续行为；`close()` 调用 `shutdownNow()`。
- [x] **Step 5: 运行红灯**：

  ```powershell
  mvn -pl agent-core -am `
    '-Dtest=ToolRegistryTest,ToolRegistryTimeoutTest' `
    '-Dsurefire.failIfNoSpecifiedTests=false' test
  ```

- [x] **Step 6: 写最小 Registry 接口**：

  ```java
  public interface ToolRegistry extends AutoCloseable {
      void register(ToolDefinition definition);
      Optional<ToolDefinition> find(String name);
      List<ToolDefinition> list();
      ToolResult execute(ToolCall call, ToolInvocationContext context);
  }
  ```

- [x] **Step 7: 写最小生产实现**：构造器注入 `ToolSchemaValidator/ToolAuthorizer/ToolAuditSink/ObjectMapper/LongSupplier nanoTime`；内部使用 `ConcurrentHashMap`、`Executors.newVirtualThreadPerTaskExecutor()` 和 `AtomicBoolean closed`；durationMs 使用单调纳秒差计算；异常先保留对象，审计后再统一用 `StringWriter/PrintWriter` 序列化。
- [x] **Step 8: 运行绿灯和核心回归**：重复指定测试并运行 `mvn -pl agent-core -am test`。
- [x] **Step 9: 提交**：

  ```text
  feat(tool): execute governed tools
  ```

### Task 6: 并发安全与 Harness 生命周期适配

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/tool/HarnessToolExecutor.java`
- Create: `agent-core/src/test/java/com/agent/core/tool/ToolRegistryConcurrencyTest.java`
- Create: `agent-core/src/test/java/com/agent/core/tool/ToolHarnessIntegrationTest.java`

- [x] **Step 1: 写失败并发测试**：使用 `CountDownLatch` 同时注册同名定义，断言仅一个 register 成功；并发执行共享原输入对象时，每个 handler 与结果互不污染；每个调用精确产生一个审计事件。
- [x] **Step 2: 写失败 Harness 测试**：真实 `StateGraph` 节点通过 `HarnessToolExecutor` 调用 Registry；成功事件顺序 `BEFORE_TOOL/AFTER_TOOL`，失败、拒绝、审批和超时顺序 `BEFORE_TOOL/FAILURE`；metadata 精确包含 `toolName/callId/riskLevel` 且不含 arguments，最终状态只从 `ToolAuditEvent.status` 读取。
- [x] **Step 3: 写失败 Hook 隔离测试**：非关键 Hook 抛错时 Harness 审计记录失败且 Registry 结果不变；关键 BEFORE_TOOL Hook 抛错时 handler 不执行且异常原样交给图失败路径。
- [x] **Step 4: 运行红灯**：

  ```powershell
  mvn -pl agent-core -am `
    '-Dtest=ToolRegistryConcurrencyTest,ToolHarnessIntegrationTest' `
    '-Dsurefire.failIfNoSpecifiedTests=false' test
  ```

- [x] **Step 5: 写最小适配器**：适配器持有 `ToolRegistry`；用 `NodeExecutionContext.callTool` 包裹调用。非 SUCCEEDED 结果在 action 内抛仅内部可见、携带结果的异常，让现有 Harness 发布 FAILURE；适配器在外层捕获该内部异常并返回原 `ToolResult`。关键 Hook 异常不捕获。
- [x] **Step 6: 运行绿灯和图回归**：重复指定测试，再运行 `mvn -pl agent-core -am test`。
- [x] **Step 7: 提交**：

  ```text
  feat(tool): bridge tool registry to harness
  ```

### Task 7: Tool Registry 确定性 EDD 与工程复盘

**Files:**
- Create: `agent-eval/src/test/java/com/agent/eval/ToolRegistryEddTest.java`
- Modify: `docs/ENGINEERING_PITFALLS.md`

- [x] **Step 1: 写 EDD**：固定六个场景 `success/schema-denied/capability-denied/approval-required/timeout/handler-failure`；报告写入 `agent-eval/target/edd/tool-registry-edd.json`，每项字段精确为 `taskId/status/audited/durationMs/errorType/passed`，回读 JSON 并校验字段集合。
- [x] **Step 2: 锁定场景证据**：每项断言 handler 调用次数、审计次数、arguments SHA-256、errorType 和状态；成功与所有失败路径都必须 `passed=true`，不能通过捕获测试异常伪造报告。
- [x] **Step 3: 更新复盘**：追加 Schema 未知关键字静默放行、JSON 节点假不可变、审批结果误执行、超时任务继续写入、审计记录敏感参数和 Harness 失败映射问题，保持“问题现象→根因→代码级解决方案”结构。
- [x] **Step 4: 运行定向 EDD**：

  ```powershell
  mvn -pl agent-eval -am `
    '-Dtest=ToolRegistryEddTest' `
    '-Dsurefire.failIfNoSpecifiedTests=false' test
  ```

- [x] **Step 5: 提交**：

  ```text
  test(eval): verify governed tool execution
  ```

### Task 8: 4A 完整门禁与里程碑审查

**Files:**
- Review: all files changed since `eba8090`
- Modify: `docs/superpowers/plans/2026-08-08-guide-fourth-part-tools-4a.md`

- [x] **Step 1: 运行完整门禁**：

  ```powershell
  $env:JAVA_HOME='C:\Program Files\Java\jdk-21'
  $env:Path="$env:JAVA_HOME\bin;$env:Path"
  mvn clean
  Push-Location agent-web/src/main/frontend
  & '.frontend/node/npm.cmd' run build
  & '.frontend/node/npm.cmd' run test:run
  Pop-Location
  mvn -pl agent-core,agent-rag,agent-web,agent-eval -am test '-Dfrontend.skip=true'
  mvn package '-DskipTests' '-Dfrontend.skip=true'
  git diff --check
  ```

- [x] **Step 2: 运行安全扫描**：只扫描根/模块 POM 与 `agent-*/src`，确认没有 `langchain4j/langgraph4j`；确认 `.env`、日志、`target`、JAR、IDE 文件未暂存；受管 Docker 容器列表为空。
- [x] **Step 3: 独立审查**：逐项对照 4A 规格；确认没有提前实现 MCP、Skills 或 AST/PTY/Playwright 适配器；无 Critical/Important 后才提交计划状态。
- [x] **Step 4: 提交门禁状态**：

  ```text
  docs(tool): complete core tool registry milestone
  ```

- [x] **Step 5: 提交后重跑核心测试**：在最终 HEAD 运行 `mvn -pl agent-core,agent-eval -am test`，检查 `git status --short` 为空；保留当前 worktree 和分支，不自动合并、推送或切换。
