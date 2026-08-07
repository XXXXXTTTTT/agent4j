# 第三篇 3B：项目知识编译 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现受工作区边界、精确文件名、完整文件 token 预算和内容哈希约束的项目知识编译器，并把文件知识与 3A RAG 证据组合为核心知识上下文。

**Architecture:** `agent-core` 只保存不可变知识请求、结果和证据协议；`agent-rag.knowledge` 负责真实路径校验、知识文件编译、热重载缓存和 RAG 组合。3B 不修改 Planner 路由、生产 Bean、Embedding 配置、数据库 schema 或前端，这些属于 3C。

**Tech Stack:** Java 21 records、`java.nio.file`、严格 UTF-8 decoder、SHA-256、`ConcurrentHashMap`、现有 `TokenEstimator`、3A `RagRetrievalPipeline`、JUnit 5、AssertJ。

---

### Task 1: 核心知识上下文协议

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/knowledge/KnowledgeEvidenceKind.java`
- Create: `agent-core/src/main/java/com/agent/core/knowledge/KnowledgeEvidenceStatus.java`
- Create: `agent-core/src/main/java/com/agent/core/knowledge/KnowledgeEvidence.java`
- Create: `agent-core/src/main/java/com/agent/core/knowledge/KnowledgeContextRequest.java`
- Create: `agent-core/src/main/java/com/agent/core/knowledge/KnowledgeContext.java`
- Create: `agent-core/src/main/java/com/agent/core/knowledge/KnowledgeContextProvider.java`
- Create: `agent-core/src/test/java/com/agent/core/knowledge/KnowledgeContextTest.java`

- [ ] **Step 1: 写失败测试**：固定公开协议为 `KnowledgeEvidenceKind.PROJECT_FILE/RAG_STAGE`、`KnowledgeEvidenceStatus.APPLIED/SKIPPED/DEGRADED`；`KnowledgeEvidence(kind, source, status, detail, errorStack)` 要求非空 source、detail，只有 `DEGRADED` 可以且必须保存完整 errorStack。`KnowledgeContextRequest(repositoryId, userId, workspaceRoot, activePath, query, complexity, maxTokens)` 将路径转为绝对规范路径并拒绝 activePath 越出根目录。`KnowledgeContext(prompt, sourceCount, fingerprint, estimatedTokens, degraded, evidence)` 冻结集合并校验 degraded 与证据一致。
- [ ] **Step 2: 运行红灯**：`mvn -pl agent-core -am "-Dtest=KnowledgeContextTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，预期 `com.agent.core.knowledge` 不存在而编译失败。
- [ ] **Step 3: 写最小实现**：所有领域对象使用 record；`KnowledgeContext.empty()` 固定返回 `"", 0, "", 0, false, List.of()`；`KnowledgeContextProvider.empty()` 先校验 request 非 null 再返回空上下文，不使用 null 或共享可变集合。
- [ ] **Step 4: 运行绿灯与核心回归**：重复指定测试，再运行 `mvn -pl agent-core -am test`。
- [ ] **Step 5: 提交**：`git commit -m "feat(core): define project knowledge protocol"`。

### Task 2: 项目知识来源领域模型

**Files:**
- Create: `agent-rag/src/main/java/com/agent/rag/knowledge/KnowledgeFileType.java`
- Create: `agent-rag/src/main/java/com/agent/rag/knowledge/KnowledgeSource.java`
- Create: `agent-rag/src/main/java/com/agent/rag/knowledge/ProjectKnowledgeContext.java`
- Create: `agent-rag/src/main/java/com/agent/rag/knowledge/ProjectKnowledgeLimitKind.java`
- Create: `agent-rag/src/main/java/com/agent/rag/knowledge/ProjectKnowledgeException.java`
- Create: `agent-rag/src/main/java/com/agent/rag/knowledge/ProjectKnowledgeLimitException.java`
- Create: `agent-rag/src/test/java/com/agent/rag/knowledge/ProjectKnowledgeDomainTest.java`

- [ ] **Step 1: 写失败测试**：`KnowledgeFileType` 精确映射 `SOUL.md/AGENTS.md/CLAUDE.md`；`KnowledgeSource(relativePath, fileType, depth, byteCount, lineCount, sha256)` 只接受 `/` 相对路径、非负深度、`0..25000` 字节、`1..200` 行和 64 位小写十六进制 SHA-256。`ProjectKnowledgeContext(prompt, sources, fingerprint, estimatedTokens)` 冻结来源并要求 source 数与 prompt/fingerprint 一致。
- [ ] **Step 2: 运行红灯**：`mvn -pl agent-rag -am "-Dtest=ProjectKnowledgeDomainTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，预期知识领域类型不存在。
- [ ] **Step 3: 写最小实现**：`ProjectKnowledgeLimitKind` 精确包含 `BYTES/LINES/TOKENS`；`ProjectKnowledgeLimitException(relativePath, kind, observed, limit)` 保存精确超限值；通用路径、I/O、UTF-8 与符号链接错误使用保留 cause 的 `ProjectKnowledgeException`。
- [ ] **Step 4: 运行绿灯**：重复指定测试并运行 `mvn -pl agent-rag -am test`。
- [ ] **Step 5: 提交**：`git commit -m "feat(rag): define project knowledge sources"`。

### Task 3: 精确知识文件扫描与安全读取

**Files:**
- Create: `agent-rag/src/main/java/com/agent/rag/knowledge/ProjectKnowledgeCompiler.java`
- Create: `agent-rag/src/test/java/com/agent/rag/knowledge/ProjectKnowledgeCompilerTest.java`

- [ ] **Step 1: 写失败测试**：在 `@TempDir` 创建真实目录树，断言加载顺序固定为根 `SOUL.md`、根到 active 目录的全部 `AGENTS.md`、根到 active 目录的全部 `CLAUDE.md`；`agents.md` 等错误大小写不加载。activePath 可为目录或文件；真实 activePath 越界、知识文件符号链接目标越界、非法 UTF-8、超过 25,000 bytes、超过 200 行均失败且 cause/observed/limit 不丢失。
- [ ] **Step 2: 运行红灯**：`mvn -pl agent-rag -am "-Dtest=ProjectKnowledgeCompilerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，预期编译器不存在。
- [ ] **Step 3: 写最小实现**：`ProjectKnowledgeCompiler(TokenEstimator)` 的 `compile(workspaceRoot, activePath, maxTokens)` 先对根和 active 执行 `toRealPath()`；通过 `Files.list(directory)` 与精确文件名等值比较发现文件，不能依赖 Windows 大小写不敏感的 `resolve/exists`。文件真实目标必须仍在 root 内；用 `CodingErrorAction.REPORT` 解码 UTF-8；行数按 `source.split("\\R", -1).length` 计算。
- [ ] **Step 4: 运行绿灯**：重复测试；Windows 无法创建符号链接时该单例测试用 assumption 明确 skip，其余门禁继续执行。
- [ ] **Step 5: 提交**：`git commit -m "feat(rag): compile bounded project knowledge files"`。

### Task 4: 完整文件 token 预算、指纹与热重载

**Files:**
- Modify: `agent-rag/src/main/java/com/agent/rag/knowledge/ProjectKnowledgeCompiler.java`
- Modify: `agent-rag/src/test/java/com/agent/rag/knowledge/ProjectKnowledgeCompilerTest.java`

- [ ] **Step 1: 写失败测试**：每个来源格式固定为 `### [TYPE] relative/path\ncontent`；选择只能在完整来源边界发生。根 `AGENTS.md` 是强制来源，其单独估算超过 maxTokens 时抛 `TOKENS`；其余来源按固定加载顺序在剩余预算内选择，不截断 UTF-8/Markdown。指纹按加载顺序对 `fileType + "\\n" + relativePath + "\\n" + sourceSha256 + "\\n"` 再做 SHA-256。相同 root/active/maxTokens 且指纹未变返回同一对象；修改内容后返回新对象与新指纹，即使 mtime 被恢复也必须失效。
- [ ] **Step 2: 运行红灯**：重复 `ProjectKnowledgeCompilerTest`，预期 token 选择、缓存对象身份或热重载断言失败。
- [ ] **Step 3: 写最小实现**：缓存键使用真实 root、真实 activePath 与 maxTokens，缓存命中条件还必须比较重新扫描得到的来源清单指纹；使用 `ConcurrentHashMap.compute` 原子替换。根 `AGENTS.md` 先保留预算，最终 prompt 仍按固定展示顺序拼接；`estimatedTokens` 必须等于对最终 prompt 的 `TokenEstimator.estimate(ChatMessage.user(prompt))`。
- [ ] **Step 4: 运行绿灯与全量 RAG 回归**：重复指定测试并运行 `mvn -pl agent-rag -am test`。
- [ ] **Step 5: 提交**：`git commit -m "feat(rag): hot reload project knowledge by content hash"`。

### Task 5: 文件知识与 RAG 组合适配器

**Files:**
- Create: `agent-rag/src/main/java/com/agent/rag/knowledge/RagKnowledgeContextProvider.java`
- Create: `agent-rag/src/test/java/com/agent/rag/knowledge/RagKnowledgeContextProviderTest.java`

- [ ] **Step 1: 写失败测试**：构造器精确注入 `ProjectKnowledgeCompiler`、`RagRetrievalPipeline`、基础 `RagRetrievalPolicy`、`TokenEstimator` 和 `strict`。断言先编译文件知识，再用剩余预算复制 policy 调用 RAG；最终 prompt 只含两个固定标题 `项目规则（受当前指令和安全策略约束）` 与 `按需检索的代码证据`，代码证据格式为 `[n] path:start-end symbol\ncontent`。sourceCount 等于文件数加实际文档数，evidence 顺序为文件后六个 RAG 阶段，最终集合不可变。
- [ ] **Step 2: 补失败隔离测试**：文件编译失败必须原样终止；RAG 结果降级时转为 `KnowledgeEvidenceStatus.DEGRADED` 并保留 errorStack；基础 RAG 异常在 `strict=true` 时保留 cause 并终止，在 `strict=false` 时返回仅文件知识并新增一条 source=`RAG_PIPELINE` 的完整降级堆栈。每次加载发布开始、文件完成、RAG 完成三个 `NodeExecutionContext.progress` 摘要。
- [ ] **Step 3: 运行红灯**：`mvn -pl agent-rag -am "-Dtest=RagKnowledgeContextProviderTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，预期适配器不存在。
- [ ] **Step 4: 写最小实现**：RAG 请求精确使用 repositoryId/query/complexity；新的 policy 只把 `maxContextTokens` 改为剩余正预算。最终 fingerprint 对项目 fingerprint 和实际代码文档的 childId、contentSource、正文 SHA-256 再做 SHA-256。最终 prompt 重新估算且不得超过 request.maxTokens；否则完整移除最低优先级代码证据，不裁断正文。
- [ ] **Step 5: 运行绿灯与回归**：重复指定测试并运行 `mvn -pl agent-core,agent-rag -am test`。
- [ ] **Step 6: 提交**：`git commit -m "feat(rag): combine project rules with code evidence"`。

### Task 6: 3B EDD、复盘与最终门禁

**Files:**
- Create: `agent-eval/src/test/java/com/agent/eval/ProjectKnowledgeEddTest.java`
- Modify: `docs/ENGINEERING_PITFALLS.md`

- [ ] **Step 1: 写确定性 EDD**：覆盖根/近端规则顺序、错误大小写忽略、SHA-256 热重载、完整文件 token 跳过、RAG 增强降级和基础 RAG 非严格回退。报告固定写入 `agent-eval/target/edd/project-knowledge-edd.json`，每项精确包含 `taskId/passed/sourceCount/fingerprint/estimatedTokens/degraded/evidence`，并回读 JSON 校验字段集合。
- [ ] **Step 2: 更新复盘**：记录 Windows 大小写不敏感路径导致错误知识文件被读取、只看 mtime 导致规则热重载失效、符号链接越界、字符截断规则、根 AGENTS 预算被可选 SOUL 挤占和 RAG 基础失败伪装空上下文等问题。
- [ ] **Step 3: 运行门禁**：JDK 21 执行 `mvn -pl agent-core,agent-rag,agent-web,agent-eval -am test`、`mvn clean package "-DskipTests" "-Dfrontend.skip=true"`、`git diff --check`、禁用依赖/禁用表述扫描和 `docker ps -a --filter label=com.agent.runtime.managed=true`。
- [ ] **Step 4: 提交**：`git commit -m "docs(engineering): record project knowledge pitfalls"`。
- [ ] **Step 5: 里程碑审查**：复核 3B 没有修改 Planner/TaskRoute、数据库 migration、生产配置或前端；独立代码审查无 Critical/Important 后，在最终 HEAD 重跑模块全量测试并保留当前 worktree/分支进入 3C。
