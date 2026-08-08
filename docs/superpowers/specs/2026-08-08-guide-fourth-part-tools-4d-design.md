# 第四篇 4D：CLI Capability 治理与安全执行设计

## 章节边界

路线图把本里程碑标为第四篇第 16 章 `CLI Capability`；教程源码
`tmp/ai-agent-guide-reference/chapters/ch09-cli-capability.html` 的页面标题为第 15 章。
本规格以路线图的 4D 边界为准，以该源码的 CLI 三层架构、安全分级、Schema 优先和错误自愈
原则为参考，不把自然语言直接当作 Shell 输入。

## 目标

在现有 `SandboxTerminalService` 之上提供结构化 CLI 命令治理层：

- 意图层只接受精确命令名、token 参数、工作区根目录、终端目标和超时。
- 命令目录固定可执行文件和固定参数前缀，并为每个命令声明风险级别与能力集合。
- 命令生成层把 token 安全引用成 Bash 字符串，拒绝 Shell 控制符、命令拼接和参数猜测。
- 安全执行层做真实路径边界检查、能力判断和分级审批，实际执行仍委托现有终端服务。
- 审批拒绝或等待审批时不启动 Bash；执行结果继续保留 PTY/Docker 的 ANSI 日志、退出码和超时。

4D 不修改 `CommandRequest`、Docker 容器清理、PTY 线程模型或 `ToolRegistry` 的既有协议；后续
第七篇综合实战再把命令目录作为受治理 Tool 注册到 Registry。4D 不实现 SSH、自动安装依赖、
隐式 `sudo`、自动重试或回滚；错误结果由上层 ReAct 图决定是否再次生成新的结构化意图。

## 公开领域协议

### 风险和审批

```java
public enum CliRiskLevel { READ_ONLY, MUTATING, DESTRUCTIVE }
public enum CliAuthorizationDecision { ALLOWED, DENIED, APPROVAL_REQUIRED }

public record CliAuthorizationContext(
        Set<RequiredCapability> grantedCapabilities,
        boolean userApproved,
        boolean administratorApproved) {}
```

`READ_ONLY` 不需要审批；`MUTATING` 需要 `userApproved=true`；`DESTRUCTIVE` 必须同时具备
`userApproved=true` 和 `administratorApproved=true`。缺少 `RequiredCapability` 永远返回
`DENIED`，不能由审批覆盖。集合在构造时去重冻结，不能根据命令名或参数文本推断能力。

### 命令定义与意图

```java
public record CliCommandDefinition(
        String name,
        String executable,
        List<String> fixedArguments,
        CliRiskLevel riskLevel,
        Set<RequiredCapability> requiredCapabilities) {}

public record CliCommandIntent(
        String name,
        List<String> arguments,
        Path workspaceRoot,
        TerminalTarget target,
        Duration timeout) {}
```

精确约束：

- `name` 使用 `[a-z][a-z0-9_.-]{0,63}`，目录内唯一；`executable` 只能是单个非空 token，
  不允许空白、控制符或 `;&|><` `$` `` ` `` 等 Shell 元字符。
- `fixedArguments` 和 `arguments` 保留顺序、不可变；每个 token 非空、最多 4096 code point，
  总用户参数最多 64 个，拒绝 NUL、换行、控制符、命令分隔符、管道、重定向、命令替换和
  环境展开字符。引号作为普通数据由渲染器安全转义。
- `workspaceRoot` 必须是现有目录并转为绝对规范路径；`target` 必须是 `PtyTarget` 或
  `DockerTarget`，实际工作目录必须位于 workspaceRoot 内。
- `timeout` 大于 0 且不超过 10 分钟；目录定义不保存 Shell 字符串、handler、类名或脚本。

### 计划、授权和执行结果

```java
public record CliCommandPlan(
        String name,
        CommandRequest request,
        CliRiskLevel riskLevel,
        String commandSha256) {}

public record CliAuthorization(
        CliAuthorizationDecision decision,
        String reason,
        CliCommandPlan plan) {}

public record CliExecutionResult(
        CliAuthorization authorization,
        Optional<CommandResult> result) {}
```

`commandSha256` 是渲染后完整 Bash 命令的 64 位小写 SHA-256；日志和审计只使用该指纹，不记录
用户参数正文。`ALLOWED` 的 `result` 在终端 Future 完成后存在；`DENIED` 和
`APPROVAL_REQUIRED` 的 `result` 为空，且绝不触发终端执行。命令退出非 0、超时或终端异常仍
由现有 `CommandResult`/Future 语义返回，不被授权层改写。

## 只读目录与安全渲染

```java
public final class CliCommandCatalog {
    public CliCommandCatalog(List<CliCommandDefinition> definitions);
    public List<CliCommandDefinition> list();
    public Optional<CliCommandDefinition> find(String name);
    public CliAuthorization authorize(
            CliCommandIntent intent,
            CliAuthorizationContext context);
}
```

目录构造一次完成全部校验并发布不可变自然名称快照；重复命令名或任一定义非法时整体失败。
`authorize` 按以下顺序执行：

1. 精确查找命令名；未知名称抛 `CliCommandNotFoundException`，不做大小写或别名回退。
2. 校验 intent 的参数数量、token 字符、超时和目标类型。
3. 对 workspaceRoot 和目标工作目录分别调用 `toRealPath()`；目标 real path 必须以 root real
   path 开头，否则抛 `CliWorkspaceViolationException`。符号链接越界同样拒绝。
4. 用单引号包裹每个 token，内部单引号精确替换为 `'\''`；只拼接定义的 executable、fixedArguments
   和 intent.arguments，不执行自然语言、表达式或额外 Shell 片段。
5. 生成 `CommandRequest` 和 commandSha256；能力缺失返回 `DENIED`，风险审批不足返回
   `APPROVAL_REQUIRED`，否则返回 `ALLOWED`。

### 执行门面

```java
public final class GovernedCliCommandExecutor {
    public GovernedCliCommandExecutor(
            CliCommandCatalog catalog,
            TerminalCommandExecutor terminalExecutor);

    public CompletableFuture<CliExecutionResult> execute(
            CliCommandIntent intent,
            CliAuthorizationContext context,
            Consumer<TerminalLog> logConsumer);
}
```

门面先同步授权，再把允许的 `CommandRequest` 精确交给 `SandboxTerminalService`。拒绝与待审批
使用已完成 Future 返回，不占用虚拟线程；终端执行失败保留原始异常 cause。门面不重试、不拼接
第二条命令、不自动批准，也不把低层 `SandboxTerminalService.execute(CommandRequest, ...)`
暴露为 Agent 的自然语言入口。

## 错误和资源语义

- `CliCommandDefinitionException`：目录定义、名称、可执行文件或固定参数非法。
- `CliCommandNotFoundException`：意图名称未注册。
- `CliArgumentException`：token、参数数量或超时非法。
- `CliWorkspaceViolationException`：root/target 不存在、符号链接越界或目标类型不支持。
- 以上异常均保存精确字段和 cause；安全失败不创建终端进程。
- `GovernedCliCommandExecutor` 关闭时关闭自己持有的 executor（若有），不关闭调用方注入的
  `TerminalCommandExecutor`；现有 `SandboxTerminalService` 的关闭和容器清理保持原语义。

## 测试门禁

### 领域单元测试

- `CliCommandDefinitionTest`：命令名、executable、固定参数、风险和能力集合边界。
- `CliCommandCatalogTest`：重复名、精确查找、缺少能力、三档审批、参数 token、命令指纹和
  workspace real path 边界；符号链接逃逸必须拒绝。
- `CliCommandRenderingTest`：空格、引号和 Unicode 参数安全引用；`;`, `|`, `>`, `$()`, 换行和
  NUL 拒绝，渲染结果不能包含未经定义的命令片段。

### 终端集成测试

`GovernedCliCommandExecutorTest` 注入现有 `TerminalCommandExecutor` fake，断言待审批与能力拒绝
的调用计数为零，允许命令只调用一次并原样返回 `CommandResult`。`GovernedCliPtyIntegrationTest`
在 `D:/Git/bin/bash.exe` 存在时使用 `PtyTarget` 和临时 workspace 实际执行 `printf`，捕获 PTY
日志并断言虚拟线程；没有该精确 Bash 文件时使用 JUnit assumption 跳过。

### EDD

`agent-eval/src/test/java/com/agent/eval/CliCapabilityEddTest.java` 生成
`agent-eval/target/edd/cli-capability-edd.json`，字段精确为
`taskId/status/decision/commandSha256/exitCode/timedOut/terminalCalls/passed`。场景覆盖只读自动
执行、可变命令待审批、破坏命令管理员审批、能力拒绝、参数注入拒绝、工作区越界拒绝和真实
PTY 输出七条路线；报告不保存命令正文或用户参数。

## 后续接口与文档

4D 只提供命令治理与现有终端适配，不把 CLI 自动重试、SSH 或多步部署流程塞进本篇。4C Skill
可以在激活后引用 CLI 命令名；第七篇 CLI Agent 再把命令定义注册为 `ToolDefinition`，统一进入
ToolRegistry 的审计和模型调用链。完成后更新 `docs/ENGINEERING_PITFALLS.md`，记录 Shell 注入、
符号链接逃逸、审批误执行和 raw CommandRequest 旁路问题。
