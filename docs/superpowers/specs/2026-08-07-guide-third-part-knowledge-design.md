# 第三篇：RAG 流水线与项目知识编译设计

## 1. 教程依据与目标

本设计依据 `fuzhengwei/ai-agent-guide` 提交 `91066e4` 的两个精确文件：

- `chapters/ch16-rag.html`：导航标题为“第10章 RAG：检索增强生成”，内部小节仍使用
  `20.x` 编号。
- `chapters/ch26-llm-wiki.html`：导航标题为“第11章 LLM-Wiki：项目知识文件”，内部小节
  仍使用 `26.x` 编号。

上游导航编号已经与 2026-08-07 总设计中的显示编号不同。本项目以后使用“第三篇 + 标题 +
精确源文件”标识范围，不根据数字推断章节。

第三篇解决两个现存产品缺口：

1. 现有 `HybridRagRetriever` 只有向量、BM25 和符号三路召回，缺少查询改写、HyDE、
   rerank、token 预算和逐阶段降级证据，而且没有生产装配。
2. 项目问答只能走无项目上下文的 `chat` 路由，或误入 Coder/Ops；仓库中的 `AGENTS.md`、
   `CLAUDE.md` 和 `SOUL.md` 没有受边界约束的编译与注入服务。

成功标准是：项目知识问答只执行 Planner 与知识检索，不触发代码修改或终端；代码任务同时
获得长期记忆、项目规则和代码检索上下文；任一增强阶段失败时保留完整证据并退回最后一个可
证明的结果；知识文件和索引变化可被稳定指纹检测。

## 2. 方案与实施分组

采用核心端口、RAG 适配器和 Web 装配三层方案：

- `agent-core` 只定义知识上下文协议、`knowledge` 路由和 Planner 状态键。
- `agent-rag` 实现可组合检索流水线、文件知识编译、代码索引协调和上下文适配。
- `agent-web` 绑定环境配置、OpenAI 兼容 Embedding API、JDBC 与生产 Bean。

第三篇分为三个可独立回归的提交组：

- **3A**：检索流水线与逐阶段证据。
- **3B**：项目知识编译器与核心知识端口。
- **3C**：`knowledge` 路由、生产索引和端到端装配。

不在本篇实现 MCP、Skills、知识图谱、任意 Wiki 写入、用户 Home 目录读取或前端低代码配置。

## 3. 3A：可组合 RAG 流水线

### 3.1 保留稳定基线

`HybridRagRetriever.search(RagQuery)` 继续作为唯一基础召回入口，保持现有三路权重、父子块、
仓库范围校验和稳定排序。旧构造器和现有测试不改变。

新增 `com.agent.rag.pipeline` 包，公开协议为：

```java
public record RagRetrievalPolicy(
        int rewriteLimit,
        boolean hydeEnabled,
        int retrievalLimit,
        int rerankLimit,
        int maxContextTokens) {}

public interface QueryRewriter {
    List<String> rewrite(String query, int limit);
}

public interface HypotheticalDocumentGenerator {
    String generate(String query);
}

public interface RagReranker {
    List<RerankedHit> rerank(String query, List<RagHit> hits, int limit);
}
```

`rewriteLimit` 取值 `1..3`，原始查询始终保留并排在第一位；改写结果去除空白、完全重复项后
不得超过该上限。HyDE 文本只用于生成向量，不参与 BM25 或符号匹配，避免假设内容污染精确
关键词召回。

### 3.2 流水线顺序与融合

`RagRetrievalPipeline.retrieve(RagRetrievalRequest)` 固定执行：

1. `QUERY_REWRITE`：按策略产生额外查询。
2. `HYDE`：启用时为原始查询生成假设文档向量。
3. `BASELINE_RETRIEVAL`：每个查询调用现有 `HybridRagRetriever`。
4. `FUSION`：按 `childId` 去重，用 Reciprocal Rank Fusion，常量 `k=60`；同分时按
   `path -> ordinal -> childId` 稳定排序。
5. `RERANK`：只处理融合后的前 `retrievalLimit` 条，输出最多 `rerankLimit` 条。
6. `TOKEN_BUDGET`：按排序选择父块；同一 `parentId` 只注入一次。

`RagContextDocument` 保存 `childId`、`parentId`、`path`、`symbol`、实际注入内容、融合分、
rerank 分、内容来源和估算 token。内容来源只含 `PARENT` 与 `CHILD`。预算优先注入完整父块；
单个父块超过剩余预算时退为对应子块；子块仍超限则跳过。若第一条子块本身超过总预算，抛出
`RagContextBudgetExceededException`，禁止返回空证据伪装成功。

token 估算复用 `agent-core.context.TokenEstimator`，生产使用现有 `Utf8TokenEstimator`。

### 3.3 证据与降级

`RagStage` 精确包含：

- `QUERY_REWRITE`
- `HYDE`
- `BASELINE_RETRIEVAL`
- `FUSION`
- `RERANK`
- `TOKEN_BUDGET`

`RagStageStatus` 只含 `APPLIED`、`SKIPPED`、`DEGRADED`。每个
`RagStageEvidence` 保存 stage、status、inputCount、outputCount、estimatedTokens、detail 和
errorStack。`errorStack` 无错误时为空字符串，有错误时保存完整堆栈。

改写、HyDE 或 rerank 失败时：

- 写 `DEGRADED` 证据；
- 保留原异常完整堆栈；
- 分别退回原始查询、无 HyDE、融合排序；
- 不把增强失败包装成基础召回失败。

Embedding、JDBC、仓库范围或基础召回失败不能降级为空结果，必须保留原 cause 并终止。
`RagRetrievalResult` 冻结 documents、evidence、estimatedTokens 和 degraded。

### 3.4 模型增强策略

模型增强通过 `ModelRouter` 适配器实现，不增加第三方向导库：

- `ModelQueryRewriter` 使用 `TaskType.QUICK_CLASSIFICATION`，严格解析 JSON 字符串数组。
- `ModelHypotheticalDocumentGenerator` 使用同一路由，要求非空纯文本。
- 默认 `RagReranker` 使用确定性的查询词覆盖率与原融合分，不增加第二次外部模型等待；接口
  允许以后替换 Cross-Encoder。

按 `TaskComplexity` 选择策略：`SIMPLE` 不改写且不启用 HyDE；`STANDARD` 最多改写两条；
`COMPLEX` 最多改写三条并启用 HyDE。三种复杂度都执行确定性 rerank 和 token 门禁。

## 4. 3B：项目知识编译

### 4.1 核心端口

`agent-core` 新增 `com.agent.core.knowledge`：

```java
public interface KnowledgeContextProvider {
    KnowledgeContext load(KnowledgeContextRequest request);
}
```

`KnowledgeContextRequest` 精确包含 repositoryId、userId、workspaceRoot、activePath、query、
`TaskComplexity` 和 maxTokens。`activePath` 必须等于 workspaceRoot 或位于其内部。

`KnowledgeContext` 保存 prompt、sourceCount、fingerprint、estimatedTokens、degraded 和不可变
`List<KnowledgeEvidence>`。空实现返回空 prompt、零来源和空证据，不使用 null。

### 4.2 精确知识文件与加载顺序

`ProjectKnowledgeCompiler` 位于 `com.agent.rag.knowledge`，只读取工作区内、大小写精确匹配的：

- 仓库根 `SOUL.md`
- 从仓库根到 `activePath` 所在目录的每一级 `AGENTS.md`
- 从仓库根到 `activePath` 所在目录的每一级 `CLAUDE.md`

不读取用户 Home 目录。多用户偏好继续由 PostgreSQL `MemoryManager` 管理，避免宿主机个人文件
跨用户泄漏。固定合并顺序为根 `SOUL.md`、各级 `AGENTS.md`、各级 `CLAUDE.md`；同类文件
由根到近端排列，后出现的近端规则优先。当前用户指令和平台安全策略始终高于项目文件。

每个 `KnowledgeSource` 保存相对路径、`KnowledgeFileType`、深度、字节数、行数和 SHA-256。
最终 `ProjectKnowledgeContext.fingerprint` 是按加载顺序连接来源类型、相对路径和来源 SHA-256
后再次计算的 SHA-256。

### 4.3 路径、大小和缓存边界

- workspaceRoot 先 `toRealPath()`，必须是目录。
- activePath 先解析真实路径，再验证仍位于真实根目录内。
- 知识文件符号链接的真实目标必须位于工作区；越界立即失败。
- 单文件上限固定 `25_000` bytes 和 `200` 行；超过任一上限抛出
  `ProjectKnowledgeLimitException`，不静默截断规则。
- 组合上下文受 `maxTokens` 约束；根 `AGENTS.md` 本身超预算时明确失败，其余来源按加载优先级
  在完整文件边界内选择，不切断 UTF-8 或 Markdown 段落。

缓存键由真实 workspaceRoot 与真实 activePath 组成。每次 load 重新计算精确来源清单和文件
SHA-256；指纹未变时返回同一不可变编译结果，变化时替换缓存。因此修改知识文件后无需重启，
同时不会仅依赖 mtime 推断内容未变。

### 4.4 RAG 与文件知识组合

`RagKnowledgeContextProvider` 先编译项目文件，再把剩余 token 预算交给
`RagRetrievalPipeline`。最终 prompt 固定分为：

1. `项目规则（受当前指令和安全策略约束）`
2. `按需检索的代码证据`

每个代码证据包含相对路径、行号、符号和编号引用。文件知识编译失败属于配置错误并终止；RAG
增强失败按 3A 规则降级；基础 RAG 不可用时可由配置选择“仅文件知识”或“严格失败”，生产默认
仅文件知识并写 `DEGRADED` 证据。

## 5. 3C：知识路由与生产闭环

### 5.1 强类型路由

`TaskRoute` 新增 `KNOWLEDGE`，`TaskKind` 新增 `PROJECT_QUERY`。合法组合固定为：

- `CHAT + CHAT + 空能力集`
- `KNOWLEDGE + PROJECT_QUERY + {CODE_READ}`
- `AGENT + 非 CHAT/PROJECT_QUERY + 非空执行能力集`

Planner 的路由模型 JSON 协议同步加入这两个枚举值。`PlannerNode` 新增状态常量：

- `KNOWLEDGE_ROUTE = "knowledge"`
- `KNOWLEDGE_CONTEXT_KEY = "planner.knowledgeContext"`
- `KNOWLEDGE_FINGERPRINT_KEY = "planner.knowledgeFingerprint"`
- `KNOWLEDGE_SOURCES_KEY = "planner.knowledgeSources"`
- `KNOWLEDGE_EVIDENCE_KEY = "planner.knowledgeEvidence"`
- `KNOWLEDGE_DEGRADED_KEY = "planner.knowledgeDegraded"`

`knowledge` 路由在 Planner 中加载知识、调用一次回答模型、写 `final_response` 后结束。生产图将
`knowledge` 与 `chat` 都映射到 `END`，只有 `agent` 进入 Coder。代码任务在生成计划前也加载
同一知识上下文。

`planner.plan` 升级为版本 `2`，动态变量精确为 task、memory 和 knowledge；新增
`planner.knowledge` 版本 `1`。旧模板版本 `1` 保留在目录中供历史指纹审计，但生产 Planner
不再用旧版本生成新计划。

### 5.2 索引一致性

`CodebaseIndexCoordinator` 在首次需要 RAG 时执行单仓库 single-flight：同一 repositoryId 的
并发请求共享一个 `CompletableFuture`，实际扫描和 embedding 在 Java 21 虚拟线程执行。

新增 PostgreSQL V4 表 `rag_repository_indexes`，保存 repositoryId、workspaceFingerprint、
parentCount、childCount 和 indexedAt。指纹基于纳入索引的相对路径与文件内容 SHA-256，不依赖
mtime。数据库指纹一致时跳过 ingest；不一致时 `CodebaseIngestionService` 完整构建后，在同一
事务替换父子块并更新索引元数据。失败时旧索引和旧指纹保持不变。

现有 `RagStore.replaceRepository` 通过兼容默认方法迁移；JDBC 实现提供带指纹的原子替换。

### 5.3 Embedding 与配置

`agent-web` 新增 OpenAI 兼容 `OpenAiEmbeddingModel`，复用现有 5 秒连接、45 秒读取超时的
Apache HTTP 客户端。请求路径、模型和 API Key 全部来自配置，日志记录 URL、模型、输入项数、
HTTP 状态和耗时，不记录源码正文或密钥。

当前数据库 V1 的精确 schema 是 `vector(8)`。本篇不伪装支持任意维度：Embedding 请求显式
发送 `dimensions=8`，响应每项必须严格为 8 个有限数；不支持该参数的端点不能启用 RAG，仍可
使用文件知识。未来改变维度必须新增显式数据库迁移，不通过运行时猜测。

新增环境配置：

- `AGENT_KNOWLEDGE_ENABLED`，默认 `true`
- `AGENT_KNOWLEDGE_MAX_TOKENS`，默认 `4000`
- `AGENT_RAG_ENABLED`，默认 `false`
- `AGENT_RAG_EMBEDDINGS_PATH`，默认 `/v1/embeddings`
- `AGENT_RAG_EMBEDDING_MODEL`，默认空
- `AGENT_RAG_REWRITE_ENABLED`，默认 `true`
- `AGENT_RAG_HYDE_ENABLED`，默认 `true`
- `AGENT_RAG_STRICT`，默认 `false`
- `AGENT_RAG_INDEX_TIMEOUT`，默认 `5m`

API Key 与 base URL 复用精确的 `AGENT_LLM_API_KEY`、`AGENT_LLM_BASE_URL`，不新增重复密钥。
配置写入根 `.env.example` 和 `agent-web/src/main/resources/application.properties`。

## 6. 错误处理与可观测性

- 项目知识路径越界、非法 UTF-8、超行数或超字节数立即失败，错误完整进入
  `planner.error`、Trace 和滚动日志。
- 增强 RAG 阶段失败写 `DEGRADED`，完整堆栈进入 `KnowledgeEvidence`；用户最终回答不暴露
  内部堆栈，只使用仍然可信的上下文。
- 索引失败不得删除旧索引；事务回滚后允许下一请求重试。
- 所有知识和 RAG 调用发布 `NodeExecutionContext.progress`；模型调用 token 继续由
  `ModelRouter` 计入 `ExecutionBudget`。
- 知识 prompt 只保存内容指纹和来源，不把完整项目规则写入普通 INFO 日志。
- EDD 报告必须记录 route、sourceCount、fingerprint、RAG 阶段、降级原因、TTFT 和最终回答
  质量门禁。

## 7. 测试与验收

### 7.1 领域测试

- 多查询去重、RRF 稳定排序、HyDE 只影响向量、rerank 协议校验。
- 每个增强阶段的成功、跳过和降级证据；异常堆栈不丢失。
- 父块去重、父转子降级、首条超预算失败和 UTF-8 token 估算。
- 知识文件精确大小写、根到近端顺序、SHA-256 热重载、符号链接越界、25KB/200 行门禁。
- `TaskDecision` 三种合法路线和所有矛盾组合。

### 7.2 集成测试

- 真实 PostgreSQL/pgvector 执行 V1→V4，验证索引元数据和原子替换。
- 同一 repositoryId 并发索引只执行一次；失败保留旧索引。
- Planner 的 `knowledge` 路由只回答不进入 Coder/Ops；代码任务同时注入 memory 与 knowledge。
- 生产属性、Embedding JSON 协议、日志脱敏和空实现兼容。

### 7.3 EDD 与全量门禁

增加项目架构问答、根/目录规则覆盖、模糊查询改写、HyDE、rerank、索引失败降级和 token 超限
任务。真实 LLM/Embedding 仅在 `AGENT_LLM_ENABLED=true` 且 RAG 配置完整时执行；否则用 JUnit
assumption 明确跳过。

最终运行 JDK 21 的：

```text
mvn -pl agent-core,agent-rag,agent-web,agent-eval -am test
mvn clean package -DskipTests -Dfrontend.skip=true
```

Docker 可用的当前环境必须执行 PostgreSQL/pgvector 集成测试；还要检查禁止依赖、Diff 空白、
受管容器残留、`.env`/日志/`target` 未进入提交，并更新 `docs/ENGINEERING_PITFALLS.md`。
