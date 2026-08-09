# 第六篇 6A 框架对比与架构守卫实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Agent4J 增加可重复的去框架化架构门禁和自研概念映射文档。

**Architecture:** 测试直接解析根 POM、`agent-core/pom.xml` 和核心源码，不修改生产运行时；映射文档
单独维护，测试验证固定端口覆盖。所有禁止项使用设计文档中明确的精确文本，不做路径或标识符猜测。

**Tech Stack:** Java 21、JUnit 5、AssertJ、JAXP DOM、Java NIO、Markdown。

---

### Task 1: 架构守卫红灯测试

**Files:**
- Create: `agent-core/src/test/java/com/agent/core/architecture/ArchitectureConstraintTest.java`

- [ ] **Step 1: Write the failing test**

写四个测试：解析根 POM 与 `agent-core/pom.xml` 的直接依赖并拒绝固定片段；递归扫描核心生产源码
并拒绝固定 import；验证八个核心端口文件存在；读取映射文档并验证固定端口和“自研”声明。

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl agent-core '-Dtest=ArchitectureConstraintTest' test`

Expected: 编译失败，因为 `docs/ARCHITECTURE_MAPPING.md` 尚不存在；测试中不得先写生产代码。

### Task 2: 映射文档与最小测试修复

**Files:**
- Create: `docs/ARCHITECTURE_MAPPING.md`

- [ ] **Step 1: Write minimal mapping document**

按设计文档写出固定端口映射表，明确 Agent4J 是自研实现，并逐项写边界差异；不添加未经代码验证的
能力描述。

- [ ] **Step 2: Run test to verify it passes**

Run: `mvn -pl agent-core '-Dtest=ArchitectureConstraintTest' test`

Expected: 4 tests passed，禁止依赖/导入、核心端口和映射文档校验全部通过。

- [ ] **Step 3: Commit**

```text
test(architecture): add framework boundary guard
```

### Task 3: 工程复盘与全量验收

**Files:**
- Modify: `docs/ENGINEERING_PITFALLS.md`

- [ ] **Step 1: Add verified pitfall entry**

记录固定现象、根因、XML/源码守卫实现和测试命令证据，避免声称扫描了不在范围内的模块。

- [ ] **Step 2: Run full verification**

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
mvn -pl agent-core '-Dtest=ArchitectureConstraintTest,GraphTopologyTest,StateGraphTopologyTest,SubgraphNodeTest' test
mvn clean package '-DskipTests' '-Dfrontend.skip=true'
git -c safe.directory='D:/agent4j/.worktrees/guide-sixth-part-6a' diff --check
git -c safe.directory='D:/agent4j/.worktrees/guide-sixth-part-6a' status --short
```

- [ ] **Step 3: Commit**

```text
docs(architecture): record framework boundary pitfalls
```
