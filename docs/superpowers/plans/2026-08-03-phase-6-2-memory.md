# Phase 6.2 Long-Term Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 PostgreSQL 长期记忆中心、严格模型提取、混合召回和 Planner Prompt 注入，并验证 `PlannerNode -> CoderNode -> OpsNode` 闭环。

**Architecture:** `agent-core` 定义不可变记忆上下文端口和 `PlannerNode`，保持对 `agent-rag` 无依赖；`agent-rag` 依赖 `agent-core`，实现模型提取、PostgreSQL 持久化、向量/词法召回和上下文适配。所有依赖构造器注入，PostgreSQL 是唯一权威源，请求内合并不形成缓存。

**Tech Stack:** Java 21、Spring JDBC、PostgreSQL 16、pgvector `vector(8)`、Jackson、ModelRouter、JUnit 5、AssertJ、Testcontainers。

---

## 文件结构

- Create: `agent-core/src/main/java/com/agent/core/memory/MemoryContextRequest.java`：Planner 记忆请求协议。
- Create: `agent-core/src/main/java/com/agent/core/memory/MemoryContext.java`：格式化上下文协议。
- Create: `agent-core/src/main/java/com/agent/core/memory/MemoryContextProvider.java`：核心层召回端口。
- Create: `agent-core/src/main/java/com/agent/core/nodes/PlannerNode.java`：记忆感知规划节点。
- Create: `agent-core/src/test/java/com/agent/core/memory/MemoryContextTest.java`：核心记忆协议测试。
- Create: `agent-core/src/test/java/com/agent/core/nodes/PlannerNodeTest.java`：Planner Prompt 与错误语义测试。
- Modify: `agent-rag/pom.xml`：增加 `agent-core` 编译依赖。
- Create: `agent-rag/src/main/java/com/agent/rag/memory/MemoryType.java`：三类精确记忆类型。
- Create: `agent-rag/src/main/java/com/agent/rag/memory/MemoryCapture.java`：原始观察输入。
- Create: `agent-rag/src/main/java/com/agent/rag/memory/MemoryDraft.java`：提取后的记忆草稿。
- Create: `agent-rag/src/main/java/com/agent/rag/memory/MemoryEntry.java`：持久化记忆协议。
- Create: `agent-rag/src/main/java/com/agent/rag/memory/MemoryQuery.java`：召回请求协议。
- Create: `agent-rag/src/main/java/com/agent/rag/memory/MemoryHit.java`：召回结果协议。
- Create: `agent-rag/src/main/java/com/agent/rag/memory/MemoryExtractor.java`：提取端口。
- Create: `agent-rag/src/main/java/com/agent/rag/memory/ModelMemoryExtractor.java`：严格 JSON 模型提取器。
- Create: `agent-rag/src/main/java/com/agent/rag/memory/MemoryExtractionException.java`：保留 cause 的提取异常。
- Create: `agent-rag/src/main/java/com/agent/rag/memory/MemoryRetrievalRow.java`：JDBC 召回行。
- Create: `agent-rag/src/main/java/com/agent/rag/memory/MemoryStore.java`：记忆存储端口。
- Create: `agent-rag/src/main/java/com/agent/rag/memory/JdbcMemoryStore.java`：事务 upsert 与两路召回。
- Create: `agent-rag/src/main/java/com/agent/rag/memory/MemoryStoreException.java`：保留 cause 的存储异常。
- Create: `agent-rag/src/main/java/com/agent/rag/memory/MemoryManager.java`：提取、hash、embedding、持久化和排序。
- Create: `agent-rag/src/main/java/com/agent/rag/memory/MemoryContextProviderAdapter.java`：将命中格式化为核心 Prompt。
- Create: `agent-rag/src/main/resources/db/migration/V2__create_memory_table.sql`：`rag_memories` 与三个索引。
- Create: `agent-rag/src/test/java/com/agent/rag/memory/MemoryDomainTest.java`：记录边界和数组防御性复制。
- Create: `agent-rag/src/test/java/com/agent/rag/memory/ModelMemoryExtractorTest.java`：严格 JSON 与路由测试。
- Create: `agent-rag/src/test/java/com/agent/rag/memory/MemoryManagerTest.java`：捕获、hash、合并和排序测试。
- Create: `agent-rag/src/test/java/com/agent/rag/memory/JdbcMemoryStoreIntegrationTest.java`：真实 pgvector 测试。
- Create: `agent-rag/src/test/java/com/agent/rag/memory/MemoryPlannerGraphTest.java`：Planner 到 Ops 闭环。
- Modify: `docs/ENGINEERING_PITFALLS.md`：记录 Phase 6.2 已验证问题、根因与修复。

## Task 1: 核心记忆端口

**Files:** the three `agent-core/.../memory` production types and `MemoryContextTest.java`.

- [ ] **Step 1: 写失败测试**：断言 `MemoryContextRequest` 拒绝空 `repositoryId`、空 `userId`、空 `query`、`limit=0` 和 `limit=21`；断言 `MemoryContext` 拒绝 null prompt 和负 entryCount，并接受精确空值 `new MemoryContext("", 0)`。
- [ ] **Step 2: 运行红灯**：`$env:JAVA_HOME='C:\Program Files\Java\jdk-21'; mvn -pl agent-core -am "-Dtest=MemoryContextTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，预期编译失败，原因为三个类型不存在。
- [ ] **Step 3: 写最小实现**：创建两个 public record，在紧凑构造器中执行设计文档的精确校验；创建 `@FunctionalInterface MemoryContextProvider`，唯一抽象方法为 `MemoryContext recall(MemoryContextRequest request)`，并添加中文 Javadoc。
- [ ] **Step 4: 运行绿灯**：重复 Step 2 命令，预期 `MemoryContextTest` 全部通过且无普通测试跳过。
- [ ] **Step 5: 提交**：仅暂存本任务六个文件，检查 `git diff --cached --check` 后执行 `git commit -m "feat(core): define memory context port"`。

## Task 2: 记忆领域协议与模型提取

**Files:** `agent-rag/pom.xml`, domain records/enums/exceptions, `MemoryExtractor.java`, `ModelMemoryExtractor.java`, `MemoryDomainTest.java`, `ModelMemoryExtractorTest.java`.

- [ ] **Step 1: 写领域红灯**：覆盖 `MemoryCapture.sourceText` 20,000 字符上限、`MemoryDraft.title` 200 字符和 content 4,000 字符上限、空字段、`MemoryQuery.types` 空集合与不可变复制、limit 边界、`MemoryEntry.embedding` 八维有限值和双向防御性复制、`MemoryHit` 有限非负分数。
- [ ] **Step 2: 写提取器红灯**：构造真实 `ModelRouter` 与捕获请求的 `LlmClient` 测试端点，断言任务类型对应 `QUICK_CLASSIFICATION` 路由、无 tools、temperature `0.0`、正确解析三种类型和空数组；分别断言未知根字段、未知 item 字段、缺字段、null、非文本、未知 type、21 项、非 `TextContent` 和路由失败均抛 `MemoryExtractionException` 且 cause 非 null。
- [ ] **Step 3: 运行红灯**：`mvn -pl agent-rag -am "-Dtest=MemoryDomainTest,ModelMemoryExtractorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，预期因领域类型和提取器不存在而编译失败。
- [ ] **Step 4: 实现领域类型**：`MemoryType` 只含三个设计值；所有 record 按设计字段顺序声明，集合用 `Set.copyOf`，embedding 用 `Arrays.copyOf`，长度和有限数校验不做文本归一化。
- [ ] **Step 5: 实现严格提取**：`agent-rag` 增加 `agent-core` 依赖；`ModelMemoryExtractor` 用构造器注入 `ModelRouter` 和 `ObjectMapper`，用 `FAIL_ON_UNKNOWN_PROPERTIES`、`FAIL_ON_MISSING_CREATOR_PROPERTIES`、`FAIL_ON_NULL_CREATOR_PROPERTIES` reader 解析内部私有 record，并在反序列化前用 `JsonNode` 精确校验类型；模型请求使用固定 system message、capture sourceText 的 user message、空 tools、null toolChoice、temperature `0.0`。
- [ ] **Step 6: 运行绿灯与回归**：重复 Step 3，并运行 `mvn -pl agent-core,agent-rag -am test`。
- [ ] **Step 7: 提交**：`git commit -m "feat(memory): extract durable memory records"`，提交内容只包含本任务文件。

## Task 3: PostgreSQL V2 与 JDBC 存储

**Files:** `V2__create_memory_table.sql`, `MemoryRetrievalRow.java`, `MemoryStore.java`, `MemoryStoreException.java`, `JdbcMemoryStore.java`, `JdbcMemoryStoreIntegrationTest.java`.

- [ ] **Step 1: 写数据库红灯**：通过 `DockerClientFactory.instance().isDockerAvailable()` 做显式 JUnit assumption；镜像精确为 `pgvector/pgvector:pg16`。测试依次执行 `V1`、`V2`，断言 `rag_memories`、`idx_rag_memories_scope`、`idx_rag_memories_search_vector`、`idx_rag_memories_embedding` 存在；断言一次 upsert 写入、重复唯一键保留 memoryId/createdAt 并更新 updatedAt、不同 repositoryId/userId/type 不互串、向量与 GIN 查询返回精确 scope、批次第二行失败时第一行回滚。
- [ ] **Step 2: 运行红灯**：`mvn -pl agent-rag -am "-Dtest=JdbcMemoryStoreIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，Docker 当前可用，测试不得 skip，预期因迁移与存储类型不存在失败。
- [ ] **Step 3: 实现迁移**：按设计 SQL 精确创建字段、约束、生成列和三个索引；不修改 `V1`，不静默处理缺失 vector 扩展。
- [ ] **Step 4: 实现端口与 JDBC**：`MemoryStore.upsertAll(List<MemoryEntry>)` 返回数据库最终条目；两路查询都接收完整 `MemoryQuery` 和 query embedding/文本。`JdbcMemoryStore` 使用 `TransactionTemplate` 和绑定参数，SQL 的 scope 条件精确为 `repository_id = ? and user_id = ? and memory_type in (...)`，type 占位符数量由非空集合精确生成，禁止拼接值；向量得分为 `1 - (embedding <=> cast(? as vector))`，词法得分为 `ts_rank_cd(search_vector, websearch_to_tsquery('simple', ?))`。
- [ ] **Step 5: 处理 upsert 返回值**：使用 `insert ... on conflict (repository_id, user_id, memory_type, content_hash) do update ... returning ...`，保留现有 memoryId/createdAt；批次在同一事务内逐行执行，任何异常包装为 `MemoryStoreException` 并保留 cause。
- [ ] **Step 6: 运行绿灯**：重复 Step 2，确认 Docker 测试执行、零 skip，并运行 `mvn -pl agent-rag -am test`。
- [ ] **Step 7: 提交**：`git commit -m "feat(memory): persist long-term memories"`。

## Task 4: MemoryManager 与核心适配器

**Files:** `MemoryManager.java`, `MemoryContextProviderAdapter.java`, `MemoryManagerTest.java`.

- [ ] **Step 1: 写捕获红灯**：注入确定性 `MemoryExtractor`、八维 `EmbeddingModel`、固定 `Clock` 和顺序 UUID supplier，断言 exact hash 输入为 `type.name() + "\n" + title + "\n" + content` 的 lowercase SHA-256，embedding 输入为 `title + "\n" + content`，条目时间和顺序精确；空提取不调用 store；21 个草稿、重复 UUID、错误维度、NaN 和 store 异常均失败且不被吞掉。
- [ ] **Step 2: 写召回红灯**：内存 store 返回重叠 vector/lexical row，断言 exact memoryId 合并、两种分数独立 min-max、全相等为零、`0.65/0.35` 合成、稳定排序、limit；另一 repositoryId/userId/type row 必须拒绝。适配器断言格式精确为 `[TYPE] title\ncontent`，条目间一个空行，空结果为 `new MemoryContext("", 0)`。
- [ ] **Step 3: 运行红灯**：`mvn -pl agent-rag -am "-Dtest=MemoryManagerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，预期因 manager 与 adapter 不存在而失败。
- [ ] **Step 4: 实现 capture**：构造器精确接收 extractor/store/model/clock/UUID supplier；校验 `EmbeddingModel.dimensions() == 8`；每次 capture 调用一次 extractor，逐条生成 hash/embedding/entry，再调用一次 `upsertAll`；校验 store 返回条目数量和 scope/type/hash 与请求完全一致。
- [ ] **Step 5: 实现 recall 与 adapter**：queryEmbedding 调用一次；分别调用 store 两路查询；以 UUID 为 key 合并但不覆盖完整 entry；独立归一化后构造 `MemoryHit`，执行四级稳定排序和 limit；adapter 只格式化 manager 返回值，不重新排序。
- [ ] **Step 6: 运行绿灯与回归**：重复 Step 3，并运行 `mvn -pl agent-rag -am test`。
- [ ] **Step 7: 提交**：`git commit -m "feat(memory): manage capture and hybrid recall"`。

## Task 5: PlannerNode 记忆注入

**Files:** `PlannerNode.java`, `PlannerNodeTest.java`.

- [ ] **Step 1: 写 Planner 红灯**：用记录请求的 `MemoryContextProvider` 和真实 `ModelRouter` 测试端点，输入精确键 `planner.repositoryId`、`planner.userId`、`planner.task`；断言 recall request 原值与 memoryLimit、模型路由 `TaskType.CODE`、system/user 两条消息、memory delimiter、temperature `0.0`、空 tools、输出 `planner.memoryContext`、`planner.plan`、`planner.model` 和 trace `planner`。
- [ ] **Step 2: 写错误红灯**：分别覆盖三个缺失输入键、memoryLimit 0/21、provider 返回 null、provider 抛错、路由失败、空 choices、非 TextContent；断言完整异常类名与堆栈位于 `planner.error`，不存在新增 `planner.plan`，输入 state 的 messages/variables 不被修改。
- [ ] **Step 3: 运行红灯**：`mvn -pl agent-core -am "-Dtest=PlannerNodeTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，预期因 `PlannerNode` 不存在失败。
- [ ] **Step 4: 写最小实现**：状态键常量与设计文档逐字一致；`execute` 依次验证输入、recall、构造两条 message、调用 CODE route、提取第一项 TextContent、写四个输出和 trace；catch `Exception` 后用 `PrintWriter/StringWriter` 写完整 stack trace 和 trace，不覆盖其他状态。
- [ ] **Step 5: 运行绿灯与回归**：重复 Step 3，随后运行 `mvn -pl agent-core -am test`。
- [ ] **Step 6: 提交**：`git commit -m "feat(core): inject long-term memory into planner"`。

## Task 6: 真实记忆到执行图闭环与复盘

**Files:** extend `JdbcMemoryStoreIntegrationTest.java`, create `MemoryPlannerGraphTest.java`, modify `docs/ENGINEERING_PITFALLS.md`.

- [ ] **Step 1: 写闭环红灯**：在真实 pgvector 测试中用固定 extractor/embedding 捕获三类记忆后召回，断言重复 capture 不新增行、exact scope/type 隔离、vector 与 GIN 均有结果、adapter 顺序等于 manager；纯 JVM 图测试构造 `PlannerNode -> CoderNode -> OpsNode`，确定性模型返回 Unified Diff 计划，Coder 应用 fixture patch，Ops 使用确定性 `SandboxTerminalService` 目标执行检查，断言 trace 精确为 `[planner, coder, ops]` 且 memory context 出现在模型请求。
- [ ] **Step 2: 运行红灯**：分别执行集成方法和 `MemoryPlannerGraphTest`，确认失败来自尚未接通的行为，而不是路径、Docker 或测试夹具错误。
- [ ] **Step 3: 最小接线修复**：只修复闭环暴露的协议连接问题；任何缺陷先保留失败断言，再修改生产代码，不增加 REST、后台任务或 Phase 6.3/6.4 能力。
- [ ] **Step 4: 更新复盘**：在 `docs/ENGINEERING_PITFALLS.md` 新增 Phase 6.2 小节，只记录测试或命令证实的 JSON 严格解析、记忆去重、scope 隔离、upsert 事务、embedding 维度、Prompt 注入和 Docker 资源治理问题。
- [ ] **Step 5: 运行阶段绿灯**：`mvn -pl agent-core,agent-rag -am clean verify`，Docker 可用时 `JdbcMemoryStoreIntegrationTest` 不得 skip。
- [ ] **Step 6: 提交**：代码/测试提交 `test(memory): verify planner memory workflow`；复盘文档单独提交 `docs(engineering): record Phase 6.2 memory pitfalls`。

## Task 7: 全量验证、合并与清理

- [ ] **Step 1: 锁定环境**：设置 `$env:JAVA_HOME='C:\Program Files\Java\jdk-21'` 与 Maven PATH；`java -version` 必须为 21.x，`mvn -version` 的 runtime 必须指向同一 JDK；不得升级到 Java 25。
- [ ] **Step 2: 全量门禁**：从仓库根运行 `mvn clean verify`，记录每个 Java 模块、Vitest、Playwright、npm audit 和 Docker 集成测试计数；任何失败先按 TDD 新增或保留可复现红灯再修复。
- [ ] **Step 3: 静态与仓库检查**：运行 `git -c safe.directory=D:/agent4j diff --check`、`git -c safe.directory=D:/agent4j status --short`、依赖树中 LangChain4j/LangGraph4j 精确扫描、`.gitignore` 检查；确认无本阶段 Docker 容器和 WinPTY/Maven 残留。
- [ ] **Step 4: 最终验收提交**：若全量验证产生新的文档证据，提交 `docs(engineering): record Phase 6.2 final verification`；没有文件变化时不创建空提交。
- [ ] **Step 5: 合并与清理**：确认功能分支为 master 祖先或执行本地 fast-forward merge，删除本阶段 worktree 和已合并分支，执行 `git worktree prune`；保留 Phase 2-5 历史 worktree。
- [ ] **Step 6: 关闭 Goal**：只有设计、实现、测试、文档、提交、全量门禁和资源清理全部完成后调用 `update_goal(status=complete)`。

## Self-review

- 设计中的所有公开类型、字段、状态键、SQL 列、索引名、JSON 字段、权重和排序键均映射到明确任务。
- 每个生产行为都有先失败测试、再最小实现、再绿灯命令，所有标识符均已精确冻结。
- 模块依赖固定为 `agent-rag -> agent-core -> agent-sandbox`，`agent-core` 不引用 `agent-rag`，不存在循环。
- 真实 PostgreSQL 门禁精确执行 `V1` 后执行 `V2`，Docker 当前环境不得通过 assumption 跳过。
