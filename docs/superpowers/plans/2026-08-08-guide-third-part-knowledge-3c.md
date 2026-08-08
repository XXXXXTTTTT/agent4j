# 第三篇 3C：知识路由与生产闭环 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 3A 检索流水线与 3B 项目知识编译器接入生产 Agent，使项目问答只读结束、代码任务同时获得记忆与知识，并用内容哈希、原子索引和 8 维 OpenAI Embedding 形成可恢复的生产闭环。

**Architecture:** `agent-core` 扩展强类型 `KNOWLEDGE/PROJECT_QUERY` 决策和 Planner 知识状态；`agent-rag` 用同一不可变源码快照生成指纹与切片，并以 PostgreSQL 元数据和单仓库 single-flight 协调索引；`agent-web` 负责独立 RAG Flyway 历史、OpenAI 兼容 Embedding、环境属性和生产 Bean。3C 不修改前端，不引入 MCP、Skills、LangChain4j 或 LangGraph4j。

**Tech Stack:** Java 21 records 与虚拟线程、Spring Boot 3.3.13、Spring JDBC/Flyway、PostgreSQL 16 + pgvector、Apache HttpClient 5、现有 `ModelRouter`/`RagRetrievalPipeline`/`ProjectKnowledgeCompiler`、JUnit 5、Testcontainers、AssertJ。

---

### Task 1: 强类型知识路由协议与确定性分类

**Files:**
- Modify: `agent-core/src/main/java/com/agent/core/intent/TaskRoute.java`
- Modify: `agent-core/src/main/java/com/agent/core/intent/TaskKind.java`
- Modify: `agent-core/src/main/java/com/agent/core/intent/TaskDecision.java`
- Modify: `agent-core/src/main/java/com/agent/core/intent/ModelIntentClassifier.java`
- Modify: `agent-core/src/main/java/com/agent/core/nodes/PlannerPromptTemplates.java`
- Modify: `agent-core/src/test/java/com/agent/core/intent/ModelIntentClassifierTest.java`
- Create: `agent-core/src/test/java/com/agent/core/intent/TaskDecisionKnowledgeTest.java`

- [ ] **Step 1: 写失败领域测试**：固定 `TaskRoute` 精确包含 `CHAT/KNOWLEDGE/AGENT`，`TaskKind` 精确新增 `PROJECT_QUERY`；合法组合只有 `CHAT + CHAT + {}`、`KNOWLEDGE + PROJECT_QUERY + {CODE_READ}`、`AGENT + 非 CHAT/PROJECT_QUERY + 非空能力集`。其余交叉组合全部构造失败。
- [ ] **Step 2: 写失败分类测试**：断言“这个项目的 PlannerNode 如何路由？”和“请解释当前仓库架构”走 `KNOWLEDGE/PROJECT_QUERY/{CODE_READ}`；“什么是 Java 虚拟线程？”走 `CHAT`；“修改 PlannerNode 并运行测试”仍走 `AGENT`。知识问答判定必须发生在普通问答回退前，但明确写入、命令或浏览器动作优先于知识问答。
- [ ] **Step 3: 运行红灯**：

  ```powershell
  mvn -pl agent-core -am `
    "-Dtest=TaskDecisionKnowledgeTest,ModelIntentClassifierTest" `
    "-Dsurefire.failIfNoSpecifiedTests=false" test
  ```

  预期因 `KNOWLEDGE`、`PROJECT_QUERY` 不存在而编译失败。
- [ ] **Step 4: 写最小实现**：扩展两个枚举与 `TaskDecision` 校验；把现有“动作词”和“代码目标词”分离，只有明确动作才进入执行路线；增加只读项目问题识别。`planner.route` 的严格 JSON 说明同步加入新枚举与唯一能力集合，不接受大小写或别名。
- [ ] **Step 5: 运行绿灯与意图回归**：重复指定测试，再运行 `mvn -pl agent-core -am test`。
- [ ] **Step 6: 提交**：

  ```text
  feat(core): add project knowledge route
  ```

### Task 2: Planner 知识回答与代码计划知识注入

**Files:**
- Modify: `agent-core/src/main/java/com/agent/core/nodes/PlannerNode.java`
- Modify: `agent-core/src/main/java/com/agent/core/nodes/PlannerPromptTemplates.java`
- Modify: `agent-core/src/test/java/com/agent/core/nodes/PlannerNodeTest.java`

- [ ] **Step 1: 写失败状态测试**：固定新增常量：

  ```java
  KNOWLEDGE_ROUTE = "knowledge";
  KNOWLEDGE_CONTEXT_KEY = "planner.knowledgeContext";
  KNOWLEDGE_FINGERPRINT_KEY = "planner.knowledgeFingerprint";
  KNOWLEDGE_SOURCES_KEY = "planner.knowledgeSources";
  KNOWLEDGE_EVIDENCE_KEY = "planner.knowledgeEvidence";
  KNOWLEDGE_DEGRADED_KEY = "planner.knowledgeDegraded";
  ```

  `KNOWLEDGE` 决策必须从 `planner.repositoryId`、`planner.userId` 和 `coder.workspacePath` 构造 `KnowledgeContextRequest`，workspaceRoot 与 activePath 均使用经过绝对规范化的工作区根路径；调用一次 `TaskType.CODE` 回答模型，写 `final_response` 与上述全部证据后返回 `knowledge`。
- [ ] **Step 2: 写失败代码任务测试**：`AGENT` 路线必须先召回 `MemoryContext` 和 `KnowledgeContext`，再渲染 `planner.plan` 版本 `2`；动态字段精确为 `task/memory/knowledge`。知识加载失败保留完整堆栈到 `planner.error`，不能继续 Coder。
- [ ] **Step 3: 写失败 Prompt 审计测试**：保留 `planner.plan` 版本 `1`，新增版本 `2`；新增 `planner.knowledge` 版本 `1`。知识回答 Prompt 明确要求按证据回答、证据不足时说明不确定性、禁止声称执行了写入或终端命令。
- [ ] **Step 4: 运行红灯**：

  ```powershell
  mvn -pl agent-core -am `
    "-Dtest=PlannerNodeTest" `
    "-Dsurefire.failIfNoSpecifiedTests=false" test
  ```

- [ ] **Step 5: 写最小实现**：为完整构造器增加 `KnowledgeContextProvider`、`knowledgeMaxTokens` 与 `ObjectMapper`；旧公开构造器注入 `KnowledgeContextProvider.empty()` 保持兼容。`planner.knowledgeEvidence` 使用 Jackson 序列化不可变证据列表，不能使用 `toString()` 伪 JSON。普通 Chat 不加载项目知识；代码任务与知识问答都发布 `NodeExecutionContext.progress`。
- [ ] **Step 6: 运行绿灯与核心回归**：重复指定测试并运行 `mvn -pl agent-core -am test`。
- [ ] **Step 7: 提交**：

  ```text
  feat(core): answer project questions with knowledge
  ```

### Task 3: 生产图知识终止路线

**Files:**
- Modify: `agent-web/src/main/java/com/agent/web/config/ProductionGraphConfiguration.java`
- Modify: `agent-web/src/test/java/com/agent/web/config/ProductionGraphConfigurationTest.java`
- Modify: `agent-web/src/test/java/com/agent/web/config/ProductionCodeAgentIntegrationTest.java`

- [ ] **Step 1: 写失败图测试**：构造注入固定 `KnowledgeContextProvider` 的图，断言 `knowledge` 与 `chat` 都直接映射 `StateGraph.END`，只有 `agent` 进入 Coder；知识问答不得出现 `coder`、`ops`、`reviewer` trace。
- [ ] **Step 2: 写失败代码回归**：代码任务仍按 Planner → Coder → Ops → Reviewer 执行，并断言 Coder 请求前 Planner 已保存知识指纹。
- [ ] **Step 3: 运行红灯**：

  ```powershell
  mvn -pl agent-web -am `
    "-Dtest=ProductionGraphConfigurationTest,ProductionCodeAgentIntegrationTest" `
    "-Dsurefire.failIfNoSpecifiedTests=false" "-Dfrontend.skip=true" test
  ```

- [ ] **Step 4: 写最小实现**：`codeAgentGraph` 精确注入 `KnowledgeContextProvider`；增加 `@ConditionalOnMissingBean(KnowledgeContextProvider.class)` 的空 Provider 保持 RAG 未装配和 knowledge 禁用模式可启动；在条件边映射中增加 `PlannerNode.KNOWLEDGE_ROUTE -> END`；`plannerRoute` 只接受 `chat/knowledge/agent/failed` 四个精确值，未知值立即失败，不能默认落入 Agent。
- [ ] **Step 5: 运行绿灯**：重复指定测试。
- [ ] **Step 6: 提交**：

  ```text
  feat(web): terminate project knowledge queries safely
  ```

### Task 4: 同源代码快照、内容指纹与切片

**Files:**
- Create: `agent-rag/src/main/java/com/agent/rag/ingest/RepositorySource.java`
- Create: `agent-rag/src/main/java/com/agent/rag/ingest/RepositorySnapshot.java`
- Create: `agent-rag/src/main/java/com/agent/rag/ingest/RepositorySourceScanner.java`
- Modify: `agent-rag/src/main/java/com/agent/rag/ingest/CodebaseChunker.java`
- Create: `agent-rag/src/test/java/com/agent/rag/ingest/RepositorySourceScannerTest.java`
- Modify: `agent-rag/src/test/java/com/agent/rag/ingest/CodebaseChunkerTest.java`

- [ ] **Step 1: 写失败扫描测试**：真实根路径内按 `/` 相对路径稳定排序，精确排除路径段 `.git`、`target`、`node_modules`；含 NUL 或非法 UTF-8 文件不进入快照；符号链接真实目标越界立即失败。`RepositorySnapshot` 保存真实 root、冻结来源和 64 位小写 SHA-256 指纹。
- [ ] **Step 2: 写失败一致性测试**：指纹按每个纳入文件的 `relativePath + "\n" + contentSha256 + "\n"` 计算；仅 mtime 变化不改变指纹，正文变化必须改变。`CodebaseChunker.chunk(snapshot, repositoryId)` 只能消费快照正文，不再次读取磁盘；测试在 capture 后改写文件，切片仍对应 capture 时正文。
- [ ] **Step 3: 运行红灯**：

  ```powershell
  mvn -pl agent-rag -am `
    "-Dtest=RepositorySourceScannerTest,CodebaseChunkerTest" `
    "-Dsurefire.failIfNoSpecifiedTests=false" test
  ```

- [ ] **Step 4: 写最小实现**：把 `CodebaseChunker` 的遍历、排除、UTF-8 与二进制判断移入 `RepositorySourceScanner`；保留 `chunk(Path, String)` 并委托 `capture`，新增 `chunk(RepositorySnapshot, String)`。JavaParser 与文本窗口只读取 `RepositorySource.content()`。
- [ ] **Step 5: 运行绿灯与 ingest 回归**：重复指定测试并运行 `mvn -pl agent-rag -am test`。
- [ ] **Step 6: 提交**：

  ```text
  refactor(rag): share repository snapshot for indexing
  ```

### Task 5: PostgreSQL V4 索引元数据与原子替换

**Files:**
- Create: `agent-rag/src/main/java/com/agent/rag/store/RagRepositoryIndex.java`
- Modify: `agent-rag/src/main/java/com/agent/rag/store/RagStore.java`
- Modify: `agent-rag/src/main/java/com/agent/rag/store/JdbcRagStore.java`
- Create: `agent-rag/src/main/resources/db/rag-migration/V4__create_repository_indexes.sql`
- Modify: `agent-rag/src/test/java/com/agent/rag/store/JdbcRagStoreIntegrationTest.java`

- [ ] **Step 1: 写失败领域测试**：`RagRepositoryIndex(repositoryId, workspaceFingerprint, parentCount, childCount, indexedAt)` 拒绝空 repositoryId、非 64 位小写哈希、负计数和 null 时间。
- [ ] **Step 2: 写失败迁移测试**：Testcontainers PostgreSQL/pgvector 执行 RAG V1→V4，断言 `rag_repository_indexes` 精确列为 `repository_id/workspace_fingerprint/parent_count/child_count/indexed_at`，repositoryId 为主键。
- [ ] **Step 3: 写失败事务测试**：新重载 `replaceRepository(repositoryId, parents, children, index)` 必须在同一事务替换父块、子块与索引元数据；故意插入无效子块后，旧块与旧指纹均保持。`findRepositoryIndex(repositoryId)` 返回 `Optional<RagRepositoryIndex>`。
- [ ] **Step 4: 运行红灯**：

  ```powershell
  mvn -pl agent-rag -am `
    "-Dtest=JdbcRagStoreIntegrationTest" `
    "-Dsurefire.failIfNoSpecifiedTests=false" test
  ```

- [ ] **Step 5: 写最小实现**：`RagStore` 保留原三参数抽象方法；新增带索引的默认重载，默认委托旧方法以兼容测试替身；新增默认 `findRepositoryIndex` 返回空。`JdbcRagStore` 覆盖两个新方法，并在现有 `TransactionTemplate` 内执行删除、写块、upsert 元数据。V4 只创建元数据表，不重复创建 V1 的 pgvector 表。
- [ ] **Step 6: 运行绿灯**：重复指定测试并运行 `mvn -pl agent-rag -am test`。
- [ ] **Step 7: 提交**：

  ```text
  feat(rag): persist repository index fingerprints
  ```

### Task 6: 虚拟线程 single-flight 索引协调

**Files:**
- Modify: `agent-rag/src/main/java/com/agent/rag/ingest/CodebaseIngestionService.java`
- Create: `agent-rag/src/main/java/com/agent/rag/index/CodebaseIndexCoordinator.java`
- Create: `agent-rag/src/main/java/com/agent/rag/knowledge/IndexingKnowledgeContextProvider.java`
- Modify: `agent-rag/src/test/java/com/agent/rag/ingest/CodebaseChunkerTest.java`
- Create: `agent-rag/src/test/java/com/agent/rag/index/CodebaseIndexCoordinatorTest.java`
- Create: `agent-rag/src/test/java/com/agent/rag/knowledge/IndexingKnowledgeContextProviderTest.java`

- [ ] **Step 1: 写失败 ingest 测试**：`CodebaseIngestionService.ingest(RepositorySnapshot, repositoryId)` 对同一快照切片、embedding，并返回与父子数量一致的 `RagRepositoryIndex`；调用带索引重载的原子替换。旧 `ingest(Path, String)` 保留并委托 scanner。
- [ ] **Step 2: 写失败协调测试**：`CodebaseIndexCoordinator.ensureIndexed(Path, String)` 返回 `CompletableFuture<RagRepositoryIndex>`；同一 repositoryId 的并发调用共享同一个未完成 Future 且只 capture/embed 一次。数据库指纹一致时跳过 ingest；不一致时更新；失败 Future 从 in-flight 移除，下一请求可重试，旧数据库索引不变。
- [ ] **Step 3: 写失败 Provider 测试**：`IndexingKnowledgeContextProvider` 等待协调 Future 的精确 `Duration` 后再调用 RAG Provider；超时、中断和执行异常保留 cause。等待在调用线程完成，但扫描与 embedding 必须运行在 `Executors.newVirtualThreadPerTaskExecutor()`。
- [ ] **Step 4: 运行红灯**：

  ```powershell
  mvn -pl agent-rag -am `
    "-Dtest=CodebaseChunkerTest,CodebaseIndexCoordinatorTest,IndexingKnowledgeContextProviderTest" `
    "-Dsurefire.failIfNoSpecifiedTests=false" test
  ```

- [ ] **Step 5: 写最小实现**：Coordinator 持有可关闭虚拟线程 Executor 与 `ConcurrentHashMap<String, CompletableFuture<RagRepositoryIndex>>`；Future 完成后以键和值双重匹配移除，避免旧 Future 删除新重试。Provider 在等待前后发布索引进度，不吞掉堆栈。
- [ ] **Step 6: 运行绿灯和并发重复测试**：指定测试连续运行三次，再运行 `mvn -pl agent-rag -am test`。
- [ ] **Step 7: 提交**：

  ```text
  feat(rag): coordinate repository indexing
  ```

### Task 7: 文件知识单独适配器与 8 维 OpenAI Embedding

**Files:**
- Create: `agent-rag/src/main/java/com/agent/rag/knowledge/ProjectFileKnowledgeContextProvider.java`
- Create: `agent-rag/src/test/java/com/agent/rag/knowledge/ProjectFileKnowledgeContextProviderTest.java`
- Create: `agent-web/src/main/java/com/agent/web/rag/OpenAiEmbeddingModel.java`
- Create: `agent-web/src/test/java/com/agent/web/rag/OpenAiEmbeddingModelTest.java`

- [ ] **Step 1: 写失败文件模式测试**：RAG 关闭时仍编译项目文件并返回 `KnowledgeContext`；证据只含 `PROJECT_FILE/APPLIED`，prompt、sourceCount、fingerprint、estimatedTokens 与 `ProjectKnowledgeCompiler` 一致，不制造六个 RAG 阶段。
- [ ] **Step 2: 写失败 HTTP 协议测试**：使用本地 `HttpServer` 捕获请求，断言 URL 使用精确配置 path，Authorization 为 Bearer，JSON 精确发送 `model`、单项 `input` 和 `dimensions: 8`。响应必须包含唯一 `index=0` 项、长度恰为 8 且所有值为有限数；缺项、重复 index、NaN/Infinity、非 2xx 和尾随 JSON 均失败并保留 cause。
- [ ] **Step 3: 写失败日志脱敏测试**：日志包含 URL、model、inputCount、HTTP 状态、durationMs；不得包含输入源码正文或 API Key。
- [ ] **Step 4: 运行红灯**：

  ```powershell
  mvn -pl agent-rag,agent-web -am `
    "-Dtest=ProjectFileKnowledgeContextProviderTest,OpenAiEmbeddingModelTest" `
    "-Dsurefire.failIfNoSpecifiedTests=false" "-Dfrontend.skip=true" test
  ```

- [ ] **Step 5: 写最小实现**：文件 Provider 复用核心证据协议；`OpenAiEmbeddingModel` 实现现有 `EmbeddingModel`，固定 `dimensions()` 为 `ChildChunk.EMBEDDING_DIMENSIONS`。构造器注入 `RestClient`、`ObjectMapper`、path、model 和用于审计的完整 URL，不拥有共享 HTTP 客户端生命周期。
- [ ] **Step 6: 运行绿灯**：重复指定测试。
- [ ] **Step 7: 提交**：

  ```text
  feat(rag): add file mode and embedding adapter
  ```

### Task 8: 生产属性、独立 RAG Flyway 与 Bean 装配

**Files:**
- Create: `agent-web/src/main/java/com/agent/web/config/KnowledgeProperties.java`
- Create: `agent-web/src/main/java/com/agent/web/config/RagProperties.java`
- Create: `agent-web/src/main/java/com/agent/web/config/KnowledgeRagConfiguration.java`
- Modify: `agent-web/src/main/java/com/agent/web/config/ModelGatewayConfiguration.java`
- Modify: `agent-web/src/main/java/com/agent/web/config/ProductionGraphConfiguration.java`
- Modify: `agent-web/src/main/resources/application.properties`
- Modify: `.env.example`
- Create: `agent-web/src/test/java/com/agent/web/config/KnowledgePropertiesTest.java`
- Create: `agent-web/src/test/java/com/agent/web/config/RagPropertiesTest.java`
- Create: `agent-web/src/test/java/com/agent/web/config/KnowledgeRagConfigurationTest.java`

- [ ] **Step 1: 写失败属性测试**：精确绑定：

  ```text
  agent.knowledge.enabled=true
  agent.knowledge.max-tokens=4000
  agent.rag.enabled=false
  agent.rag.embeddings-path=/v1/embeddings
  agent.rag.embedding-model=
  agent.rag.rewrite-enabled=true
  agent.rag.hyde-enabled=true
  agent.rag.strict=false
  agent.rag.index-timeout=5m
  ```

  RAG 启用时 embedding model 必填、path 必须以 `/` 开头、timeout 必须为正；知识 maxTokens 必须为正。
- [ ] **Step 2: 写失败模式装配测试**：knowledge 禁用时注入 `KnowledgeContextProvider.empty()`；knowledge 开启且 RAG 关闭时注入 `ProjectFileKnowledgeContextProvider`；两者都开启时依次装配 `OpenAiEmbeddingModel`、`JdbcRagStore`、`HybridRagRetriever`、3A pipeline、Coordinator、`IndexingKnowledgeContextProvider`。
- [ ] **Step 3: 写失败迁移隔离测试**：Web 默认 Flyway 继续使用 `flyway_schema_history` 与 `db/migration`；RAG 启用时新增独立 `Flyway`，location 精确为 `classpath:db/rag-migration`，history table 精确为 `flyway_rag_schema_history`。不得把两个 location 合并，否则 Web V1/V2 与 RAG V1/V2 版本冲突。
- [ ] **Step 4: 运行红灯**：

  ```powershell
  mvn -pl agent-web -am `
    "-Dtest=KnowledgePropertiesTest,RagPropertiesTest,KnowledgeRagConfigurationTest" `
    "-Dsurefire.failIfNoSpecifiedTests=false" "-Dfrontend.skip=true" test
  ```

- [ ] **Step 5: 写最小实现**：Embedding `RestClient` 复用 `modelGatewayHttpClient` 的 5 秒连接与 45 秒响应超时；API Key/base URL 只读取现有 `agent.llm` 属性。基础 policy 使用 rewriteLimit=3、retrievalLimit=20、rerankLimit=8、maxContextTokens 由 Provider 改写；`rewrite-enabled=false` 时 rewriteLimit=1，`hyde-enabled` 精确传入 policy。Coordinator Bean 使用 `destroyMethod="close"`。
- [ ] **Step 6: 更新环境样例**：只写占位配置，不写真实 key；`.env.example` 增加设计列出的全部 `AGENT_KNOWLEDGE_*` 与 `AGENT_RAG_*` 环境变量。
- [ ] **Step 7: 运行绿灯与 Spring 回归**：重复指定测试并运行 `mvn -pl agent-web -am test "-Dfrontend.skip=true"`。
- [ ] **Step 8: 提交**：

  ```text
  feat(web): wire production project knowledge
  ```

### Task 9: 真实 PostgreSQL 知识闭环、EDD 与最终门禁

**Files:**
- Create: `agent-web/src/test/java/com/agent/web/config/ProductionKnowledgeIntegrationTest.java`
- Create: `agent-eval/src/test/java/com/agent/eval/ProjectKnowledgeRouteEddTest.java`
- Modify: `docs/ENGINEERING_PITFALLS.md`

- [x] **Step 1: 写生产集成测试**：当前 Docker 环境用真实 `pgvector/pgvector:pg16`、独立 RAG Flyway 和固定 8 维测试 Embedding；项目架构问题只执行 Planner 并返回 `final_response`，状态包含项目文件与代码证据；代码修改任务同时包含 memory 与 knowledge 后才进入 Coder。并发同仓库首次查询只建立一次索引。
- [x] **Step 2: 写故障集成测试**：索引更新中 embedding 失败时旧块和旧指纹仍可读取；非严格模式最终回答仅使用项目文件并记录 `DEGRADED`；严格模式以完整 cause 终止。知识问题不得产生 `ops.command`、`coder.unifiedDiff` 或 Reviewer 证据。
- [x] **Step 3: 写确定性 EDD**：报告固定写入 `agent-eval/target/edd/project-knowledge-route-edd.json`，每项字段精确为 `taskId/route/sourceCount/fingerprint/ragStages/degraded/ttftMs/finalResponse/passed`；覆盖普通 Chat、项目问答、代码任务、索引命中跳过、增强降级、基础失败回退。
- [x] **Step 4: 更新工程复盘**：追加路由动作词误判、Flyway 双版本空间、mtime 假命中、指纹与切片双扫描竞态、single-flight 旧 Future 删除新重试、Embedding 维度漂移与日志泄密等问题。
- [x] **Step 5: 运行定向 EDD**：

  ```powershell
  mvn -pl agent-eval -am `
    "-Dtest=ProjectKnowledgeRouteEddTest" `
    "-Dsurefire.failIfNoSpecifiedTests=false" test
  ```

- [x] **Step 6: 运行完整门禁**：先清理 target，再构建前端静态资源，然后运行 JDK 21 全量测试和不再 clean 的打包；避免 `clean + frontend.skip` 清空真实浏览器测试与最终 JAR 所需的 `target/classes/static`：

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

- [x] **Step 7: 运行安全与残留检查**：精确扫描 `pom.xml`、模块 POM 与 `agent-*/src`，确认没有 `langchain4j/langgraph4j`；确认 `.env`、日志、`target` 未暂存；`docker ps -a --filter "label=com.agent.runtime.managed=true"` 无残留。
- [x] **Step 8: 提交**：

  ```text
  test(eval): verify project knowledge production loop
  ```

- [x] **Step 9: 里程碑审查**：独立代码审查无 Critical/Important；最终 HEAD 重跑完整门禁。保留当前 worktree 与分支，不自动合并、不推送，等待第四篇实施。
