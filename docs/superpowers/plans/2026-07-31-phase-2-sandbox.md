# Phase 2 Code Sandbox Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付 JavaParser AST 提取、JGit Unified Diff 应用、Docker/PTY 异步 Bash 执行，以及可接入 `StateGraph` 的 `CoderNode` 与 `OpsNode`。

**Architecture:** `agent-sandbox` 提供不依赖 `agent-core` 的代码与终端能力，`agent-core` 通过编译依赖调用这些能力。AST 与 Diff 使用 JavaParser 和 JGit 的结构化 API；`SandboxTerminalService` 用自有虚拟线程执行器路由 Docker 与 PTY 后端，节点只依赖 `TerminalCommandExecutor` 协议。

**Tech Stack:** Java 21、JavaParser 3.28.2、JGit 7.7.1.202607240634-r、Docker-Java 3.7.1、pty4j 0.13.12、JUnit 5、AssertJ、Maven 3.8.8。

---

## 文件结构

- `pom.xml`：集中保存 Phase 2 依赖版本。
- `agent-sandbox/pom.xml`：JavaParser、JGit、Docker-Java、pty4j 与测试依赖。
- `agent-core/pom.xml`：增加对 `agent-sandbox` 的编译依赖。
- `agent-sandbox/src/main/java/com/agent/sandbox/ast/*`：AST 结果 record、强类型异常、类/方法提取与安全 Diff 应用。
- `agent-sandbox/src/main/java/com/agent/sandbox/pty/*`：终端协议 record、PTY 后端、异步路由服务与强类型异常。
- `agent-sandbox/src/main/java/com/agent/sandbox/docker/DockerCommandExecutor.java`：一次性 Docker 容器执行与清理。
- `agent-core/src/main/java/com/agent/core/nodes/CoderNode.java`：应用 Unified Diff 并更新不可变状态。
- `agent-core/src/main/java/com/agent/core/nodes/OpsNode.java`：执行 Bash 并更新不可变状态。
- `agent-sandbox/src/test/java/com/agent/sandbox/ast/*`：真实 Java 21 源码与临时 Git 仓库测试。
- `agent-sandbox/src/test/java/com/agent/sandbox/pty/*`：终端协议、真实 Git Bash 与路由测试。
- `agent-sandbox/src/test/java/com/agent/sandbox/docker/DockerCommandExecutorTest.java`：真实 Docker 容器集成测试。
- `agent-core/src/test/java/com/agent/core/nodes/*`：节点状态和完整图闭环测试。

所有 Maven 命令先在当前 PowerShell 会话设置：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
```

### Task 1: Phase 2 依赖边界

**Files:**
- Modify: `pom.xml`
- Modify: `agent-sandbox/pom.xml`
- Modify: `agent-core/pom.xml`

- [ ] **Step 1: 写依赖声明**

父 POM 精确增加以下属性：

```xml
<javaparser.version>3.28.2</javaparser.version>
<jgit.version>7.7.1.202607240634-r</jgit.version>
<docker-java.version>3.7.1</docker-java.version>
<pty4j.version>0.13.12</pty4j.version>
```

`agent-sandbox` 增加 `javaparser-core`、`org.eclipse.jgit`、
`docker-java-core`、`docker-java-transport-httpclient5`、`pty4j` 和测试范围的
`spring-boot-starter-test`。`agent-core` 增加同版本 reactor 模块依赖：

```xml
<dependency>
    <groupId>com.agent</groupId>
    <artifactId>agent-sandbox</artifactId>
    <version>${project.version}</version>
</dependency>
```

- [ ] **Step 2: 验证依赖解析与模块方向**

Run: `mvn -pl agent-core -am -DskipTests compile`

Expected: reactor 自动按 `agent-sandbox`、`agent-core` 排序并且全部编译成功。

- [ ] **Step 3: 检查禁止依赖**

Run: `mvn -pl agent-core,agent-sandbox -am dependency:tree`

Expected: 输出包含上述五个制品，且不含 `langchain4j`、`langgraph4j`。

- [ ] **Step 4: 提交依赖边界**

```text
build(sandbox): 集成代码解析与终端执行依赖
```

### Task 2: JavaParser AST 提取

**Files:**
- Create: `agent-sandbox/src/test/java/com/agent/sandbox/ast/AstServiceTest.java`
- Create: `agent-sandbox/src/main/java/com/agent/sandbox/ast/ClassInfo.java`
- Create: `agent-sandbox/src/main/java/com/agent/sandbox/ast/MethodInfo.java`
- Create: `agent-sandbox/src/main/java/com/agent/sandbox/ast/AstServiceException.java`
- Create: `agent-sandbox/src/main/java/com/agent/sandbox/ast/AstService.java`

- [ ] **Step 1: 写 AST 失败测试**

用 `@TempDir` 写入包含 package、外部类、内部类、两个重载方法和 Java 21 record
pattern 的 UTF-8 源文件。测试精确调用：

```java
ClassInfo classInfo = service.extractClass(sourceFile, "example.Sample");
List<MethodInfo> methods = service.extractMethods(sourceFile, "example.Sample");
```

断言 `qualifiedName`、起止行、源码片段和方法源码顺序；`declaration` 使用
`getDeclarationAsString(true, true, true)` 的结果区分重载。另断言传入
`"Sample"`、不存在的完整限定名、语法错误文件与不存在路径均抛出
`AstServiceException`，并验证解析异常保留 cause。

- [ ] **Step 2: 运行测试并确认红灯**

Run: `mvn -pl agent-sandbox -Dtest=AstServiceTest test`

Expected: 测试编译失败，精确原因是 AST 类型尚不存在。

- [ ] **Step 3: 实现不可变结果与异常**

```java
public record ClassInfo(
        String qualifiedName, int beginLine, int endLine, String source) {
}

public record MethodInfo(
        String name, String declaration, int beginLine, int endLine, String source) {
}

public final class AstServiceException extends RuntimeException {
    public AstServiceException(String message) { super(message); }
    public AstServiceException(String message, Throwable cause) { super(message, cause); }
}
```

record 紧凑构造器拒绝 null、空名称、非正行号及 `endLine < beginLine`；公开类型、
构造器和方法使用中文 Javadoc。

- [ ] **Step 4: 实现 Java 21 AST 提取**

`AstService` 构造 `new JavaParser(new ParserConfiguration().setLanguageLevel(
ParserConfiguration.LanguageLevel.JAVA_21))`。`parse(Path)` 使用 UTF-8，要求
`ParseResult.isSuccessful()` 且结果存在。类定位只比较
`ClassOrInterfaceDeclaration.getFullyQualifiedName()` 与传入完整限定名的
`String.equals`；结果数量不为 1 时抛异常。

源码切片只使用 JavaParser `Range.begin/end` 的一基行列和原始 UTF-8 字符串，
包含结束列字符。方法只遍历目标类 `getMembers()` 中直接声明的
`MethodDeclaration`，不递归进入内部类，并以成员顺序返回 `List.copyOf`。

- [ ] **Step 5: 运行 AST 测试并确认绿灯**

Run: `mvn -pl agent-sandbox -Dtest=AstServiceTest test`

Expected: 类、重载方法、源码范围、Java 21 语法与异常测试全部通过。

### Task 3: JGit Unified Diff 应用

**Files:**
- Create: `agent-sandbox/src/test/java/com/agent/sandbox/ast/AstServiceDiffTest.java`
- Modify: `agent-sandbox/src/main/java/com/agent/sandbox/ast/AstService.java`

- [ ] **Step 1: 写 Diff 失败测试**

每个测试用 `Git.init().setDirectory(repositoryRoot.toFile()).call()` 创建真实临时
仓库。有效补丁把 `src/main/java/example/Sample.java` 中 `return 1` 修改为
`return 2`，断言返回值精确等于该文件的绝对规范路径、文件内容已更新且结果
列表不可修改。分别测试空补丁、非 Git 目录、冲突补丁和包含 `../outside.txt`
的新文件补丁；越界测试还必须断言工作树外文件未创建。

- [ ] **Step 2: 运行 Diff 测试并确认红灯**

Run: `mvn -pl agent-sandbox -Dtest=AstServiceDiffTest test`

Expected: 测试失败，精确原因是 `AstService.applyDiff` 尚不存在。

- [ ] **Step 3: 实现安全 JGit Apply**

```java
public List<Path> applyDiff(Path repositoryRoot, String unifiedDiff)
```

实现顺序必须固定：

1. 拒绝 null、空补丁和不存在的目录，并用 `toRealPath()` 固定根目录。
2. 用 `Git.open(root.toFile())` 验证其 `Repository.getWorkTree().toPath().toRealPath()`
   精确等于根目录。
3. 将补丁编码一次为 UTF-8 字节；用 `Patch.parse(InputStream)` 解析，拒绝
   `Patch.getErrors()` 非空。
4. 对每个 `FileHeader.getOldPath()` 和 `getNewPath()`，跳过精确字符串
   `DiffEntry.DEV_NULL`；直接解析 JGit 返回值，拒绝绝对路径，并要求
   `root.resolve(path).normalize().startsWith(root)` 为 true。
5. 把同一字节通过 `git.apply().setPatch(new ByteArrayInputStream(bytes)).call()`
   应用。
6. 把 `ApplyResult.getUpdatedFiles()` 转为绝对规范路径并再次执行根目录边界检查，
   最后 `List.copyOf` 返回。

所有 IOException、GitAPIException 与运行时解析错误统一包装为保留 cause 的
`AstServiceException`，已经是 `AstServiceException` 的实例直接重新抛出。

- [ ] **Step 4: 运行全部 AST 测试**

Run: `mvn -pl agent-sandbox -Dtest=AstServiceTest,AstServiceDiffTest test`

Expected: 两个测试类全部通过，越界补丁没有产生工作树外文件。

- [ ] **Step 5: 提交 AST 与 Diff**

```text
feat(sandbox): 实现 AST 提取与 Unified Diff 应用
```

### Task 4: 终端协议与 PTY 后端

**Files:**
- Create: `agent-sandbox/src/test/java/com/agent/sandbox/pty/TerminalModelTest.java`
- Create: `agent-sandbox/src/test/java/com/agent/sandbox/pty/PtyCommandExecutorTest.java`
- Create: `agent-sandbox/src/main/java/com/agent/sandbox/pty/TerminalTarget.java`
- Create: `agent-sandbox/src/main/java/com/agent/sandbox/pty/DockerTarget.java`
- Create: `agent-sandbox/src/main/java/com/agent/sandbox/pty/PtyTarget.java`
- Create: `agent-sandbox/src/main/java/com/agent/sandbox/pty/Stream.java`
- Create: `agent-sandbox/src/main/java/com/agent/sandbox/pty/TerminalLog.java`
- Create: `agent-sandbox/src/main/java/com/agent/sandbox/pty/CommandRequest.java`
- Create: `agent-sandbox/src/main/java/com/agent/sandbox/pty/CommandResult.java`
- Create: `agent-sandbox/src/main/java/com/agent/sandbox/pty/TerminalCommandExecutor.java`
- Create: `agent-sandbox/src/main/java/com/agent/sandbox/pty/SandboxExecutionException.java`
- Create: `agent-sandbox/src/main/java/com/agent/sandbox/pty/PtyCommandExecutor.java`

- [ ] **Step 1: 写协议校验失败测试**

覆盖 null target、空 Bash 命令、零/负 timeout、空 Docker 镜像、空容器目录、
不存在的宿主目录、不存在的 Bash 文件、不存在的 PTY 工作目录和 null 日志/
结果字符串。断言 `TerminalTarget` 精确允许 `DockerTarget` 与 `PtyTarget`，
`Stream.values()` 精确等于 `[STDOUT, STDERR, PTY]`。

- [ ] **Step 2: 写真实 PTY 失败测试**

使用 `Path.of("D:/Git/bin/bash.exe")`；该精确路径不存在时通过
`Assumptions.assumeTrue` 跳过。分别执行：正常 stdout、包含 ANSI 的输出、
`exit 7`、以及 `sleep 2` 配合 `Duration.ofMillis(100)`。日志 consumer 收集
`TerminalLog`，断言全部日志为 `Stream.PTY`，stdout 保留原始 ANSI，stderr
精确为空字符串，非零退出码为 7，超时结果精确为 `(-1, ..., "", true)`。

- [ ] **Step 3: 运行测试并确认红灯**

Run: `mvn -pl agent-sandbox -Dtest=TerminalModelTest,PtyCommandExecutorTest test`

Expected: 测试编译失败，精确原因是终端协议与 PTY 执行器尚不存在。

- [ ] **Step 4: 实现终端协议**

```java
public sealed interface TerminalTarget permits DockerTarget, PtyTarget {}
public enum Stream { STDOUT, STDERR, PTY }
public record CommandRequest(TerminalTarget target, String bashCommand, Duration timeout) {}
public record CommandResult(int exitCode, String stdout, String stderr, boolean timedOut) {}
public record TerminalLog(Stream stream, String text) {}
public record DockerTarget(String image, Path hostWorkspace, String containerWorkspace)
        implements TerminalTarget {}
public record PtyTarget(Path bashExecutable, Path workingDirectory)
        implements TerminalTarget {}

@FunctionalInterface
public interface TerminalCommandExecutor {
    CompletableFuture<CommandResult> execute(
            CommandRequest request, Consumer<TerminalLog> logConsumer);
}
```

每个 record 按设计文档执行精确校验，并保存绝对规范化的宿主路径；
`SandboxExecutionException` 提供 message/cause 构造器并保留原始 cause。

- [ ] **Step 5: 实现 PTY 执行器**

`PtyCommandExecutor.execute(PtyTarget, String, Duration, Consumer<TerminalLog>)`
使用以下 pty4j 调用：

```java
PtyProcess process = new PtyProcessBuilder()
        .setCommand(new String[] {
                target.bashExecutable().toString(), "-lc", bashCommand})
        .setDirectory(target.workingDirectory().toString())
        .setRedirectErrorStream(true)
        .start();
```

用 Java 21 虚拟线程读取 `process.getInputStream()` 的原始字节，每个非空片段按
UTF-8 追加 stdout 并推送 `new TerminalLog(Stream.PTY, text)`。当前执行线程用
`process.waitFor(timeout.toMillis(), MILLISECONDS)` 等待；超时后
`destroyForcibly()` 并等待退出，返回 `exitCode=-1` 与 `timedOut=true`。非超时
返回真实 `process.exitValue()`。启动、读取、等待和 consumer 异常包装为
`SandboxExecutionException`，中断时恢复 interrupt 标志。

- [ ] **Step 6: 运行终端协议与 PTY 测试**

Run: `mvn -pl agent-sandbox -Dtest=TerminalModelTest,PtyCommandExecutorTest test`

Expected: 协议测试与全部真实 Git Bash 测试通过，PTY 测试 skipped 为 0。

- [ ] **Step 7: 提交 PTY 能力**

```text
feat(sandbox): 实现终端协议与 PTY 执行器
```

### Task 5: Docker 后端与异步路由服务

**Files:**
- Create: `agent-sandbox/src/test/java/com/agent/sandbox/docker/DockerCommandExecutorTest.java`
- Create: `agent-sandbox/src/test/java/com/agent/sandbox/pty/SandboxTerminalServiceTest.java`
- Create: `agent-sandbox/src/main/java/com/agent/sandbox/docker/DockerCommandExecutor.java`
- Create: `agent-sandbox/src/main/java/com/agent/sandbox/pty/SandboxTerminalService.java`

- [ ] **Step 1: 写真实 Docker 失败测试**

用 Docker-Java `pingCmd().exec()` 和 `inspectImageCmd("python:3.12-slim").exec()`
检查前置条件，不满足时以 JUnit assumption 明确跳过；本机最终验证要求 skipped
为 0。使用 `@TempDir` 作为挂载目录和容器目录 `/workspace`，验证：

- `printf out; printf err >&2; printf changed > result.txt` 能写宿主文件；
- stdout、stderr 与实时 `TerminalLog` 按 `STDOUT`/`STDERR` 捕获；
- `exit 9` 返回真实退出码且 `timedOut=false`；
- `sleep 2` 在 100 ms 超时后返回 `exitCode=-1`、`timedOut=true`；
- 每次执行后 `listContainersCmd().withShowAll(true).withLabelFilter(
  Map.of("com.agent.runtime.managed", "true")).exec()` 结果为空。

- [ ] **Step 2: 写路由服务失败测试**

使用真实 `PtyTarget` 验证 `execute` 在命令完成前立即返回未完成的
`CompletableFuture`，最终任务内 `Thread.currentThread().isVirtual()` 为 true。
用 DockerTarget 验证强类型路由；`close()` 后再次 execute 精确抛出
`IllegalStateException`。consumer 抛出的测试异常必须以
`CompletionException -> SandboxExecutionException -> 原测试异常` 的 cause 链
返回。

- [ ] **Step 3: 运行测试并确认红灯**

Run: `mvn -pl agent-sandbox -Dtest=DockerCommandExecutorTest,SandboxTerminalServiceTest test`

Expected: 测试编译失败，精确原因是 Docker 执行器与路由服务尚不存在。

- [ ] **Step 4: 实现 Docker-Java 客户端与容器生命周期**

`DockerCommandExecutor` 默认构造器使用真实依赖 API：

```java
DefaultDockerClientConfig config = DefaultDockerClientConfig
        .createDefaultConfigBuilder().build();
DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
        .dockerHost(config.getDockerHost())
        .sslConfig(config.getSSLConfig())
        .build();
DockerClient client = DockerClientImpl.getInstance(config, httpClient);
```

`execute(DockerTarget, String, Duration, Consumer<TerminalLog>)` 创建容器时精确设置：

```java
HostConfig hostConfig = HostConfig.newHostConfig().withBinds(new Bind(
        target.hostWorkspace().toString(),
        new Volume(target.containerWorkspace()), AccessMode.rw));

dockerClient.createContainerCmd(target.image())
        .withCmd("bash", "-lc", bashCommand)
        .withWorkingDir(target.containerWorkspace())
        .withHostConfig(hostConfig)
        .withLabels(Map.of("com.agent.runtime.managed", "true"))
        .exec();
```

启动后用 `logContainerCmd` 的 `ResultCallback.Adapter<Frame>` 按
`Frame.getStreamType()` 分离 UTF-8 stdout/stderr 并推送日志；用
`WaitContainerResultCallback.awaitStatusCode(timeout.toMillis(), MILLISECONDS)`
读取退出码。返回 null 表示超时，此时 stop 容器并返回 `-1`。`finally` 始终
执行 `removeContainerCmd(id).withForce(true).exec()`；已有主异常时把清理异常
加入 suppressed，无主异常时清理异常成为 `SandboxExecutionException`。`close`
关闭 DockerClient 并保留 close 异常 cause。

- [ ] **Step 5: 实现虚拟线程异步路由**

`SandboxTerminalService` 实现 `TerminalCommandExecutor` 与 `AutoCloseable`，持有
`Executors.newVirtualThreadPerTaskExecutor()`、`DockerCommandExecutor`、
`PtyCommandExecutor` 和 `AtomicBoolean closed`。`execute` 先同步校验 request 与
consumer，再用 `CompletableFuture.supplyAsync(..., executor)`；只用 Java 21
模式匹配精确路由 `DockerTarget` 与 `PtyTarget`。任务异常统一转换为
`SandboxExecutionException`。`close` 原子关闭服务、虚拟线程执行器和 Docker
客户端；关闭后拒绝新命令。

- [ ] **Step 6: 运行 Docker 与路由测试**

Run: `mvn -pl agent-sandbox -Dtest=DockerCommandExecutorTest,SandboxTerminalServiceTest test`

Expected: Docker 与 PTY 路由测试全部通过，Docker 测试和 PTY 测试 skipped 为 0。

- [ ] **Step 7: 运行 sandbox 回归测试**

Run: `mvn -pl agent-sandbox test`

Expected: AST、Diff、终端协议、PTY、Docker 和路由测试全部通过。

- [ ] **Step 8: 提交沙箱执行服务**

```text
feat(sandbox): 实现 Docker 与异步终端服务
```

### Task 6: CoderNode 与 OpsNode 图闭环

**Files:**
- Create: `agent-core/src/test/java/com/agent/core/nodes/CoderNodeTest.java`
- Create: `agent-core/src/test/java/com/agent/core/nodes/OpsNodeTest.java`
- Create: `agent-core/src/test/java/com/agent/core/nodes/CoderOpsGraphTest.java`
- Create: `agent-core/src/main/java/com/agent/core/nodes/CoderNode.java`
- Create: `agent-core/src/main/java/com/agent/core/nodes/OpsNode.java`

- [ ] **Step 1: 写 CoderNode 失败测试**

真实临时 Git 仓库中写入源码和有效补丁，初始状态精确使用
`coder.workspacePath`、`coder.unifiedDiff`。断言节点返回新状态、原状态未变、
文件已修改、`coder.updatedFiles` 是相对工作树路径并以换行连接、trace 精确追加
`coder`。分别测试缺少两个输入和冲突补丁，断言 `coder.error` 包含完整 Java
异常类名、消息和 `at ` 堆栈行。

- [ ] **Step 2: 写 OpsNode 失败测试**

用严格检查 `CommandRequest.target()`、`bashCommand()`、`timeout()` 的测试
`TerminalCommandExecutor` 返回 `completedFuture(new CommandResult(7,
"out", "err", false))`，断言四个结果键和 trace。另返回 exceptionally completed
future，断言 `ops.error` 包含异常链与 `at ` 堆栈行。缺少 `ops.command` 也必须
写错误状态，不把非零退出码转换为错误。

- [ ] **Step 3: 写图闭环失败测试**

初始化真实 Git 仓库，让 CoderNode 把 `value.txt` 从 `before` 修改为 `after`。
测试执行器在 `OpsNode` 收到精确命令 `cat value.txt` 后从该工作树读取内容并
返回。图结构精确为：

```java
graph.addNode("coder", state -> coderNode.execute(state)
                .withVariable("test.coderVirtual",
                        Boolean.toString(Thread.currentThread().isVirtual())))
        .addNode("ops", state -> opsNode.execute(state)
                .withVariable("test.opsVirtual",
                        Boolean.toString(Thread.currentThread().isVirtual())))
        .addEdge("coder", "ops")
        .addEdge("ops", StateGraph.END)
        .setEntryPoint("coder");
```

断言 stdout 为 `after`、两个虚拟线程标记均为 `true`，trace 精确等于
`[coder, ops]`。

- [ ] **Step 4: 运行测试并确认红灯**

Run: `mvn -pl agent-core -am -Dtest=CoderNodeTest,OpsNodeTest,CoderOpsGraphTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: core 测试编译失败，精确原因是两个节点尚不存在。

- [ ] **Step 5: 实现 CoderNode**

公开常量精确如下：

```java
public static final String WORKSPACE_PATH_KEY = "coder.workspacePath";
public static final String UNIFIED_DIFF_KEY = "coder.unifiedDiff";
public static final String UPDATED_FILES_KEY = "coder.updatedFiles";
public static final String ERROR_KEY = "coder.error";
```

构造器注入非 null `AstService`。`execute` 精确读取两个键，拒绝缺失或空值，
调用 `applyDiff`，把 `workspace.toAbsolutePath().normalize().relativize(updated)`
的结果替换 `\` 为 `/` 后以 `"\n"` 连接，写入 `coder.updatedFiles` 并追加
trace `coder`。捕获全部 `Exception`，用 `StringWriter` 与 `printStackTrace` 写入
`coder.error` 后追加同一 trace；状态更新只通过 `AgentState` 的 with 方法。

- [ ] **Step 6: 实现 OpsNode**

公开常量精确如下：

```java
public static final String COMMAND_KEY = "ops.command";
public static final String EXIT_CODE_KEY = "ops.exitCode";
public static final String STDOUT_KEY = "ops.stdout";
public static final String STDERR_KEY = "ops.stderr";
public static final String TIMED_OUT_KEY = "ops.timedOut";
public static final String ERROR_KEY = "ops.error";
```

构造器拒绝 null executor、target、timeout 及非正 timeout。`execute` 读取并校验
命令，构造 `CommandRequest`，传入空操作日志 consumer，并用 `future.get()` 等待。
成功后用 `Integer.toString`、stdout/stderr、`Boolean.toString` 写入全部结果键并
追加 trace `ops`。捕获全部 `Exception`；中断时恢复 interrupt 标志；错误用
`printStackTrace` 写入 `ops.error` 并追加同一 trace。

- [ ] **Step 7: 运行节点与图测试**

Run: `mvn -pl agent-core -am -Dtest=CoderNodeTest,OpsNodeTest,CoderOpsGraphTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: 节点状态、错误堆栈和 `CoderNode -> OpsNode -> END` 全部通过。

- [ ] **Step 8: 运行 core 与 sandbox 回归测试**

Run: `mvn -pl agent-core -am test`

Expected: Phase 1 与 Phase 2 的全部 core/sandbox 测试通过。

- [ ] **Step 9: 提交节点闭环**

```text
feat(core): 实现代码修改与命令执行节点
```

### Task 7: 全量验证与交付

**Files:**
- Modify only if verification exposes a Phase 2 defect.

- [ ] **Step 1: 运行完整干净构建**

Run: `mvn clean verify`

Expected: 五个 reactor project 全部 `SUCCESS`，所有测试零失败、零错误；
`DockerCommandExecutorTest` 和 `PtyCommandExecutorTest` skipped 均为 0。

- [ ] **Step 2: 检查依赖树**

Run: `mvn dependency:tree`

Expected: 不含 `langchain4j` 与 `langgraph4j`，不存在模块循环。

- [ ] **Step 3: 检查 Java 21 与不可变约束**

Run: `rg -n "newVirtualThreadPerTaskExecutor|record |sealed interface TerminalTarget|JAVA_21" agent-core/src agent-sandbox/src`

Expected: 图引擎、终端服务、PTY 读取、状态与协议 record、JavaParser 语言级别
均有源码证据。

- [ ] **Step 4: 检查 Docker 容器残留**

Run: `docker ps -aq --filter "label=com.agent.runtime.managed=true"`

Expected: 无输出。

- [ ] **Step 5: 检查提交范围与空白错误**

Run: `git diff --check`、`git status --short`、`git log --oneline --decorate -12`

Expected: 无空白错误；工作树干净；Phase 2 提交均符合
`<type>(<scope>): <description>`。

- [ ] **Step 6: 独立代码审查**

逐项复核设计文档中的 AST、Diff、Docker、PTY、节点、测试和 Phase 2 边界，
记录所有发现并在相应模块以 TDD 修复；重新执行本任务 Step 1 至 Step 5。
