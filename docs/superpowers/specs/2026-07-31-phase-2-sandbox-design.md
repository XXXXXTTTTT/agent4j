# Phase 2 代码工具与沙箱设计

## 目标

在 Phase 1 图引擎基础上实现代码理解、增量修改与命令执行闭环。Phase 2
交付 JavaParser AST 提取、JGit Unified Diff 应用、Docker 与 PTY 双后端
Bash 执行，以及可直接加入 `StateGraph` 的 `CoderNode` 与 `OpsNode`。

所有阻塞式命令执行均显式运行在 Java 21 虚拟线程中。任何工具异常都保留
完整堆栈并写入 `AgentState`，使后续节点可以读取错误并进入修复循环。

## 依赖与模块关系

依赖版本来自 2026-07-31 Maven Central `maven-metadata.xml` 的 `release`：

- `com.github.javaparser:javaparser-core:3.28.2`
- `org.eclipse.jgit:org.eclipse.jgit:7.7.1.202607240634-r`
- `com.github.docker-java:docker-java-core:3.7.1`
- `com.github.docker-java:docker-java-transport-httpclient5:3.7.1`
- `org.jetbrains.pty4j:pty4j:0.13.12`

`agent-sandbox` 依赖以上组件并保持不依赖 `agent-core`。`agent-core` 增加对
`agent-sandbox` 的编译依赖，使蓝图规定的 `com.agent.core.nodes.CoderNode`
和 `OpsNode` 可以调用沙箱能力。该依赖方向不存在模块循环。

## AST 服务

`com.agent.sandbox.ast.AstService` 是无共享可变状态的服务，公开以下方法：

```java
ClassInfo extractClass(Path sourceFile, String qualifiedClassName);
List<MethodInfo> extractMethods(Path sourceFile, String qualifiedClassName);
List<Path> applyDiff(Path repositoryRoot, String unifiedDiff);
```

`qualifiedClassName` 必须与 JavaParser 得到的完整限定类名精确相等，不执行
大小写、简单类名或后缀匹配。找不到唯一类时抛出 `AstServiceException`。

`ClassInfo` 是包含 `qualifiedName`、`beginLine`、`endLine` 和 `source` 的
不可变 record。`MethodInfo` 是包含 `name`、`declaration`、`beginLine`、
`endLine` 和 `source` 的不可变 record。方法列表只包含目标类直接声明的
方法，保留源码顺序；重载方法通过 `declaration` 明确区分。

JavaParser 使用 `ParserConfiguration.LanguageLevel.JAVA_21`。源文件不存在、
解析失败、位置信息缺失或类定位不唯一时，统一抛出保留 cause 的
`AstServiceException`。

## Diff 应用

`applyDiff` 使用 JGit `Patch` 预解析 UTF-8 Unified Diff，并在写文件前验证每个
非 `/dev/null` 新旧路径均位于工作树根目录内；验证通过后将同一补丁字节交给
JGit `ApplyCommand`，不使用字符串替换模拟补丁。调用前要求 `repositoryRoot`
是现有 Git 工作树根目录；调用后将 JGit 返回的更新文件转为绝对规范路径，
并再次验证每个路径都位于工作树根目录内。空补丁、非 Git 目录、解析问题、
补丁冲突和越界路径均抛出 `AstServiceException`。

返回列表按 JGit 的更新顺序冻结为不可修改列表。服务不自动提交代码，不修改
Git 配置，也不处理远程仓库。

## 终端模型

`com.agent.sandbox.pty.TerminalCommandExecutor` 是供节点依赖的函数式接口：

```java
CompletableFuture<CommandResult> execute(
        CommandRequest request,
        Consumer<TerminalLog> logConsumer);
```

协议类型均为不可变 record，并置于 `com.agent.sandbox.pty`：

- `CommandRequest(TerminalTarget target, String bashCommand, Duration timeout)`
- `CommandResult(int exitCode, String stdout, String stderr, boolean timedOut)`
- `TerminalLog(Stream stream, String text)`
- `DockerTarget(String image, Path hostWorkspace, String containerWorkspace)`
- `PtyTarget(Path bashExecutable, Path workingDirectory)`

`TerminalTarget` 是只允许 `DockerTarget` 与 `PtyTarget` 的 sealed interface。
`Stream` 精确包含 `STDOUT`、`STDERR`、`PTY`。`CommandRequest` 拒绝 null target、
空命令和非正超时；`DockerTarget` 拒绝空镜像、空容器工作目录和不存在的宿主
工作目录；`PtyTarget` 拒绝不存在的 Bash 可执行文件与工作目录；结果与日志
record 拒绝 null 字符串。命令超时时 `CommandResult.exitCode` 固定为 `-1`，
`timedOut` 固定为 `true`；未超时时返回进程或容器的实际退出码。

## SandboxTerminalService

`com.agent.sandbox.pty.SandboxTerminalService` 实现
`TerminalCommandExecutor` 与 `AutoCloseable`。它拥有
`Executors.newVirtualThreadPerTaskExecutor()`，`execute` 立即返回
`CompletableFuture`，并根据强类型 target 路由到 Docker 或 PTY 执行器。

### Docker 后端

`com.agent.sandbox.docker.DockerCommandExecutor` 使用 Docker-Java：

1. 创建带 `com.agent.runtime.managed=true` 标签的一次性容器。
2. 将 `hostWorkspace` 以读写方式绑定到 `containerWorkspace`。
3. 在容器工作目录执行参数数组 `bash`, `-lc`, `bashCommand`。
4. 分别捕获 stdout 与 stderr，并将每个日志帧传给 consumer。
5. 在 timeout 内等待退出并读取精确 exit code。
6. 超时后终止容器，返回 `timedOut=true`。
7. 在 `finally` 中强制删除容器；清理失败作为 suppressed exception 保留。

容器创建、日志等待、退出等待与清理都在服务虚拟线程任务内完成。镜像名称由
`DockerTarget` 完整传入，生产代码没有默认镜像。

### PTY 后端

`com.agent.sandbox.pty.PtyCommandExecutor` 使用 pty4j 启动参数数组
`bashExecutable`, `-lc`, `bashCommand`，工作目录精确取自 `PtyTarget`。
PTY 合并后的原始输出写入 `stdout` 并作为 `Stream.PTY` 推送，`stderr` 返回
空字符串。超时后强制终止进程并返回 `timedOut=true`。ANSI 控制序列不删除。

服务关闭后拒绝新命令并关闭虚拟线程执行器。启动、I/O、Docker API 与日志
consumer 异常统一封装为 `SandboxExecutionException`，原始 cause 不丢失。

## CoderNode

`com.agent.core.nodes.CoderNode` 实现 Phase 1 的 `Node`，构造器注入
`AstService`。状态键定义为公开常量，精确值如下：

- `WORKSPACE_PATH_KEY = "coder.workspacePath"`
- `UNIFIED_DIFF_KEY = "coder.unifiedDiff"`
- `UPDATED_FILES_KEY = "coder.updatedFiles"`
- `ERROR_KEY = "coder.error"`

执行时读取工作树与补丁，调用 `AstService.applyDiff`，将相对工作树的更新文件
以换行符连接后写入 `coder.updatedFiles`，并追加 trace `coder`。缺少输入或
应用失败时不修改文件结果字段，将完整 Java 堆栈写入 `coder.error` 并追加
相同 trace。

## OpsNode

`com.agent.core.nodes.OpsNode` 构造器注入 `TerminalCommandExecutor`、
`TerminalTarget` 与 `Duration`。状态键定义为公开常量，精确值如下：

- `COMMAND_KEY = "ops.command"`
- `EXIT_CODE_KEY = "ops.exitCode"`
- `STDOUT_KEY = "ops.stdout"`
- `STDERR_KEY = "ops.stderr"`
- `TIMED_OUT_KEY = "ops.timedOut"`
- `ERROR_KEY = "ops.error"`

执行时读取 Bash 命令，等待异步结果并写入全部结果字段，随后追加 trace
`ops`。非零 exit code 是正常命令结果，不转换为 Java 异常。调度、等待或
执行失败时将完整 Java 堆栈写入 `ops.error` 并追加 trace `ops`。

## 测试策略

实现遵循红、绿、重构循环：

- `AstServiceTest` 使用临时 Java 21 源文件验证完整限定类名、重载方法、行号、
  源码与找不到类的异常。
- `AstServiceDiffTest` 初始化真实临时 Git 仓库，验证有效 Unified Diff、冲突
  补丁、更新路径与文件内容。
- `PtyCommandExecutorTest` 使用已验证的 `D:/Git/bin/bash.exe` 运行输出、ANSI、
  非零退出与超时测试；路径不存在时使用 JUnit assumption 明确跳过。
- `DockerCommandExecutorTest` 使用当前已存在且验证含 Bash 5.2.37 的
  `python:3.12-slim`，验证挂载工作区、stdout/stderr、实时日志、非零退出、
  超时与容器清理；Docker Engine 或该镜像不可用时使用 assumption 明确跳过。
- `CoderNodeTest` 使用真实 `AstService` 和临时 Git 仓库验证代码修改与错误堆栈。
- `OpsNodeTest` 使用 `TerminalCommandExecutor` 测试实现验证全部状态字段与错误
  堆栈。
- `CoderOpsGraphTest` 运行 `CoderNode -> OpsNode -> END`，证明补丁应用后命令
  能读取修改结果，并验证两个节点都由 Phase 1 图引擎的虚拟线程调度。

当前开发环境的 Docker 测试不得跳过；最终验证必须明确报告 Docker 测试实际
执行数量。完整交付运行 `mvn clean verify`，检查依赖树不含禁止的 Agent 库，
并确认测试后没有遗留带 `com.agent.runtime.managed=true` 标签的容器。

## Phase 2 边界

本阶段不引入 java-tree-sitter，因为 Phase 2 路线图明确要求先集成 JavaParser；
不实现 Phase 3 的 Playwright、ModelRouter 与 ReviewerNode，也不实现 Phase 4
的 Checkpointer、HITL 与数据库持久化。Docker 镜像构建、镜像仓库管理、网络
策略和资源配额留给后续沙箱集群工程化。
