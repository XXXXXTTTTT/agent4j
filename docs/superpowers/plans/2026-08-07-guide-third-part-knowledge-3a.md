# 第三篇 3A：可组合 RAG 流水线 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有三路代码检索器之上实现查询改写、可选 HyDE、RRF、确定性 rerank、token 预算和逐阶段降级证据。

**Architecture:** `HybridRagRetriever` 保持基础召回算法并实现新的 `RagRetriever` 端口；`agent-rag.pipeline` 用不可变协议编排全部增强阶段。模型增强通过 `ModelRouter` 适配器注入，增强失败返回最后一个可证明的检索结果，基础召回失败继续终止。

**Tech Stack:** Java 21 records、Virtual Threads、现有 `ModelRouter`、Jackson、JUnit 5、AssertJ、现有 PostgreSQL/pgvector 基线。

---

### Task 1: RAG 流水线领域协议

**Files:**
- Create: `agent-rag/src/main/java/com/agent/rag/pipeline/RagRetrievalPolicy.java`
- Create: `agent-rag/src/main/java/com/agent/rag/pipeline/RagStage.java`
- Create: `agent-rag/src/main/java/com/agent/rag/pipeline/RagStageStatus.java`
- Create: `agent-rag/src/main/java/com/agent/rag/pipeline/RagStageEvidence.java`
- Create: `agent-rag/src/main/java/com/agent/rag/pipeline/RagContentSource.java`
- Create: `agent-rag/src/main/java/com/agent/rag/pipeline/RagContextDocument.java`
- Create: `agent-rag/src/main/java/com/agent/rag/pipeline/RagRetrievalRequest.java`
- Create: `agent-rag/src/main/java/com/agent/rag/pipeline/RagRetrievalResult.java`
- Create: `agent-rag/src/main/java/com/agent/rag/pipeline/FusedHit.java`
- Create: `agent-rag/src/main/java/com/agent/rag/pipeline/RerankedHit.java`
- Create: `agent-rag/src/main/java/com/agent/rag/pipeline/QueryRewriter.java`
- Create: `agent-rag/src/main/java/com/agent/rag/pipeline/HypotheticalDocumentGenerator.java`
- Create: `agent-rag/src/main/java/com/agent/rag/pipeline/RagReranker.java`
- Create: `agent-rag/src/main/java/com/agent/rag/pipeline/RagContextBudgetExceededException.java`
- Create: `agent-rag/src/test/java/com/agent/rag/pipeline/RagPipelineDomainTest.java`

- [ ] **Step 1: 写失败测试**：断言策略精确边界 `rewriteLimit=1..3` 且包含原始查询、全部正数限制、六个 `RagStage`、三个 `RagStageStatus`；`RagRetrievalRequest` 冻结 repositoryId/query/complexity/policy；所有集合执行 `List.copyOf`；分数必须有限非负；父子内容行号和 token 必须有效；降级结果必须至少有一条 `DEGRADED` 证据，非降级结果禁止包含该状态。
- [ ] **Step 2: 运行红灯**：`mvn -pl agent-rag -am "-Dtest=RagPipelineDomainTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，预期因 `com.agent.rag.pipeline` 类型不存在而编译失败。
- [ ] **Step 3: 写最小实现**：按规格创建不可变 record、enum、三个注入端口和预算异常；调用方通过精确构造器或 lambda 注入禁用行为，协议层不保存共享可变状态且不使用 null。
- [ ] **Step 4: 运行绿灯**：重复指定测试，随后运行 `mvn -pl agent-rag -am test`。
- [ ] **Step 5: 提交**：`git commit -m "feat(rag): define retrieval pipeline protocol"`。

### Task 2: 基础检索端口与 RRF

**Files:**
- Create: `agent-rag/src/main/java/com/agent/rag/search/RagRetriever.java`
- Modify: `agent-rag/src/main/java/com/agent/rag/search/HybridRagRetriever.java`
- Create: `agent-rag/src/main/java/com/agent/rag/pipeline/ReciprocalRankFusion.java`
- Create: `agent-rag/src/test/java/com/agent/rag/pipeline/ReciprocalRankFusionTest.java`
- Modify: `agent-rag/src/test/java/com/agent/rag/search/HybridRagRetrieverTest.java`

- [ ] **Step 1: 写失败测试**：三组有重叠 childId 的结果使用 `k=60` 融合；断言重复命中分数累加、单组排名顺序、同分按 `path/ordinal/childId` 排序、空组返回空；同一 childId 内容不一致明确失败。增加编译断言证明 `HybridRagRetriever` 可赋值给 `RagRetriever`。
- [ ] **Step 2: 运行红灯**：`mvn -pl agent-rag -am "-Dtest=ReciprocalRankFusionTest,HybridRagRetrieverTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，预期端口和融合类型不存在。
- [ ] **Step 3: 写最小实现**：`RagRetriever` 只声明 `List<RagHit> search(RagQuery query)`；现有类实现接口，不改变搜索逻辑；`ReciprocalRankFusion` 使用 `1.0 / (60 + rank)`，rank 从 1 开始，输出冻结的 `FusedHit(RagHit hit, double score)`。
- [ ] **Step 4: 运行绿灯与旧基线**：重复测试并运行真实 `JdbcRagStoreIntegrationTest`；Docker 可用时不得 skip。
- [ ] **Step 5: 提交**：`git commit -m "feat(rag): add reciprocal rank fusion"`。

### Task 3: 确定性 rerank

**Files:**
- Create: `agent-rag/src/main/java/com/agent/rag/pipeline/LexicalCoverageReranker.java`
- Create: `agent-rag/src/main/java/com/agent/rag/pipeline/RerankValidation.java`
- Create: `agent-rag/src/test/java/com/agent/rag/pipeline/LexicalCoverageRerankerTest.java`

- [ ] **Step 1: 写失败测试**：复用 `Bm25Scorer.tokenize`，以 `FusedHit.score` 为检索分，断言 `rerankScore = 0.7 * queryCoverage + 0.3 * normalizedRetrievalScore`；中文/英文 token、空覆盖、稳定同分顺序均有测试。验证外部 reranker 返回未知 childId、重复 childId、非有限分数、超过 limit 或 null 时失败。
- [ ] **Step 2: 运行红灯**：`mvn -pl agent-rag -am "-Dtest=LexicalCoverageRerankerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，预期两个生产类型不存在。
- [ ] **Step 3: 写最小实现**：覆盖率使用查询唯一 token 在 `child.content + parent.content + symbol + path` 中的命中比例；检索分按本批 min/max 归一化；按 rerankScore、原检索分、path、ordinal、childId 排序。`RerankValidation` 返回验证后的不可变列表。
- [ ] **Step 4: 运行绿灯**：重复指定测试并运行 `mvn -pl agent-rag -am test`。
- [ ] **Step 5: 提交**：`git commit -m "feat(rag): add deterministic reranking"`。

### Task 4: 父子上下文 token 门禁

**Files:**
- Create: `agent-rag/src/main/java/com/agent/rag/pipeline/RagTokenBudgetSelector.java`
- Create: `agent-rag/src/test/java/com/agent/rag/pipeline/RagTokenBudgetSelectorTest.java`

- [ ] **Step 1: 写失败测试**：固定 `TokenEstimator`，断言相同 parentId 只注入一次；父块可容纳时使用 `PARENT`；父块超剩余预算但子块可容纳时使用 `CHILD`；后续文档超限时完整跳过；第一条子块也超总预算时抛 `RagContextBudgetExceededException` 并保存 estimated/limit；结果 token 总数不超过上限。
- [ ] **Step 2: 运行红灯**：`mvn -pl agent-rag -am "-Dtest=RagTokenBudgetSelectorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，预期选择器不存在。
- [ ] **Step 3: 写最小实现**：选择器精确接收 `List<FusedHit>`、已验证的 `List<RerankedHit>` 和 maxTokens，以 childId 映射回父子正文并按完整文档为原子单位选择；`RagContextDocument` 的路径、符号、行号和内容来源与实际注入正文一致；不得截断字符串。
- [ ] **Step 4: 运行绿灯**：重复指定测试并运行 `mvn -pl agent-rag -am test`。
- [ ] **Step 5: 提交**：`git commit -m "feat(rag): enforce retrieval token budget"`。

### Task 5: 总流水线、HyDE 与失败隔离

**Files:**
- Create: `agent-rag/src/main/java/com/agent/rag/pipeline/RagRetrievalPipeline.java`
- Create: `agent-rag/src/main/java/com/agent/rag/pipeline/RagPipelineException.java`
- Create: `agent-rag/src/test/java/com/agent/rag/pipeline/RagRetrievalPipelineTest.java`

- [ ] **Step 1: 写失败测试**：记录 `RagRetriever` 收到的精确 `RagQuery`，断言原始查询始终第一、`rewriteLimit` 包含原始查询、传给 rewriter 的 limit 等于剩余数量、改写去空白去重且受限；`SIMPLE/STANDARD/COMPLEX` 分别应用 1/最多 2/最多 3 条查询，只有 `COMPLEX` 且策略允许时执行 HyDE；HyDE 只替换原始查询 embedding，BM25 文本仍是原查询；六阶段证据顺序固定。分别让 rewriter、HyDE、reranker 抛错，断言返回结果、`DEGRADED` 和完整堆栈；让 EmbeddingModel 或基础 retriever 抛错，断言流水线终止且 cause 不丢失。
- [ ] **Step 2: 运行红灯**：`mvn -pl agent-rag -am "-Dtest=RagRetrievalPipelineTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，预期流水线不存在。
- [ ] **Step 3: 写最小实现**：构造器精确注入 `RagRetriever`、`EmbeddingModel`、三个增强端口和 `TokenEstimator`；按设计顺序执行。增强异常转成证据，基础异常包装为 `RagPipelineException`；错误堆栈使用 `StringWriter/PrintWriter` 完整保存。
- [ ] **Step 4: 运行绿灯与回归**：重复测试并运行 `mvn -pl agent-rag -am test`。
- [ ] **Step 5: 提交**：`git commit -m "feat(rag): orchestrate adaptive retrieval"`。

### Task 6: ModelRouter 查询改写与 HyDE 适配器

**Files:**
- Create: `agent-rag/src/main/java/com/agent/rag/pipeline/RagPromptTemplates.java`
- Create: `agent-rag/src/main/java/com/agent/rag/pipeline/ModelQueryRewriter.java`
- Create: `agent-rag/src/main/java/com/agent/rag/pipeline/ModelHypotheticalDocumentGenerator.java`
- Create: `agent-rag/src/test/java/com/agent/rag/pipeline/ModelRetrievalEnhancerTest.java`

- [ ] **Step 1: 写失败测试**：使用现有 `RestClient + MockRestServiceServer + LlmClient + ModelRouter` 模式返回真实 Chat Completions JSON。断言 rewriter 严格接受 JSON 字符串数组，拒绝对象、Markdown fence、空项、超过 limit、非 TextContent；HyDE 接受非空 TextContent，拒绝工具内容；两者使用 `TaskType.QUICK_CLASSIFICATION` 且温度 `0.0`。
- [ ] **Step 2: 运行红灯**：`mvn -pl agent-rag -am "-Dtest=ModelRetrievalEnhancerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，预期三个类型不存在。
- [ ] **Step 3: 写最小实现**：Prompt 使用 `PromptCatalog` 固定 `rag.rewrite@1` 与 `rag.hyde@1`；只解析模型 TextContent，不从自然语言猜测 JSON；异常保留端点、模型和原 cause。
- [ ] **Step 4: 运行绿灯与模型回归**：重复测试并运行 `mvn -pl agent-core,agent-rag -am test`。
- [ ] **Step 5: 提交**：`git commit -m "feat(rag): add model retrieval enhancers"`。

### Task 7: 3A EDD、复盘与最终门禁

**Files:**
- Create: `agent-eval/src/test/java/com/agent/eval/RagPipelineEddTest.java`
- Modify: `agent-eval/src/test/java/com/agent/eval/LlmEddTest.java`
- Modify: `docs/ENGINEERING_PITFALLS.md`

- [ ] **Step 1: 写确定性 EDD**：任务集覆盖模糊查询改写、HyDE、重复查询 RRF、rerank、父转子 token 降级与三种增强失败。JSON 报告写入 `agent-eval/target/edd/rag-pipeline-edd.json`，精确包含 taskId、passed、documents、estimatedTokens、degraded 和六阶段证据。
- [ ] **Step 2: 扩展真实 LLM EDD**：仅在 `AGENT_LLM_ENABLED=true` 时增加查询改写与 HyDE 输出协议任务；关闭时 assumption 明确 skip。
- [ ] **Step 3: 更新复盘**：记录多查询分数不可直接相加、HyDE 污染 BM25、rerank 协议越界、token 按字符裁断破坏证据、增强错误吞栈等已验证问题。
- [ ] **Step 4: 运行全量门禁**：JDK 21 执行 `mvn -pl agent-core,agent-rag,agent-web,agent-eval -am test`、显式 LLM EDD 开关测试、`mvn clean package "-DskipTests" "-Dfrontend.skip=true"`、`git diff --check`、禁止依赖/表述扫描和 Docker 受管容器清理检查。
- [ ] **Step 5: 提交**：只暂存本任务三份文件，提交 `docs(engineering): record adaptive rag pitfalls`。

### Task 8: 3A 里程碑审查

**Files:**
- Review only: all files changed since `2284943`

- [ ] **Step 1: 逐项对照规格**：确认 3A 没有提前实现 3B/3C，旧 `HybridRagRetriever` 公开行为兼容，所有集合不可变，所有增强错误有证据。
- [ ] **Step 2: 提交后重新验证**：在最终 HEAD 重跑 `mvn -pl agent-core,agent-rag,agent-web,agent-eval -am test`，从 Surefire XML 汇总 tests/failures/errors/skipped。
- [ ] **Step 3: 分支处理**：保留 `feat/guide-third-part-knowledge` worktree，继续在同一第三篇分支执行 3B；不合并、不删除、不推送，除非用户给出新的精确指令。
