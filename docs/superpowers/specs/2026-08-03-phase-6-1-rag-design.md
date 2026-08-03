# Phase 6.1 Codebase RAG Core Design

## Goal

在 `agent-rag` 中建立可独立测试的 Codebase RAG 核心：把代码库切成父子文档，使用
JavaParser AST 符号信息建立索引，使用 PostgreSQL `pgvector` 生成向量召回集，使用
PostgreSQL GIN 全文索引生成词法召回集，并由 Java 实现 BM25 与三路混合排序。

本阶段不实现 MemoryManager、模型调用、Langfuse、OpenTelemetry、Benchmark、REST 或
WebSocket。embedding 通过构造器注入，测试使用确定性八维模型；后续阶段可以替换模型而
不改变切片、存储和检索协议。

## Boundaries

`agent-rag` 可以依赖 `agent-sandbox` 的 `AstService` 和 `agent-core` 的基础类型，但
`agent-core`、`agent-web` 不依赖 `agent-rag`。PostgreSQL 是索引的唯一权威源；进程内对象
只负责一次请求的召回项合并，不缓存或裁决索引状态。

所有公开标识符、状态字段、数据库列和 JSON 字段执行精确匹配。路径统一保存为仓库根目录
下的 `/` 分隔相对路径；绝对路径、空白 repositoryId、空白 query、负数行号和错误向量
维度均明确拒绝。

## Domain Protocol

### Embedding

`com.agent.rag.embedding.EmbeddingModel` 是构造器注入端口：

```java
public interface EmbeddingModel {
    int dimensions();
    float[] embed(String text);
}
```

`embed` 不得返回 null、空数组或与 `dimensions()` 不一致的数组。Phase 6.1 的 schema
固定 `vector(8)`；生产模型适配器必须先声明八维协议，改变维度需要新的迁移和版本。

### Chunk records

`ParentChunk` 是一个稳定语义单元，字段为 `parentId`、`repositoryId`、`path`、
`symbol`、`content`、`startLine`、`endLine`、`metadataJson`。Java 类使用完整限定名作为
`symbol`；非 Java 文件使用 null symbol。

`ChildChunk` 是父块内的检索单元，字段为 `childId`、`parentId`、`repositoryId`、`path`、
`symbol`、`ordinal`、`content`、`startLine`、`endLine`、`embedding`。`ordinal` 从 0 开始，
同一父块内严格递增。

`RagQuery` 字段为 `repositoryId`、`query`、`queryEmbedding`、`limit`。queryEmbedding 为
null 时由 `EmbeddingModel` 计算；非 null 时仍校验八维协议。limit 范围固定为 1 到 100。

`RagHit` 返回 `childChunk`、`parentChunk`、`vectorScore`、`bm25Score`、`symbolScore`、
`finalScore`。所有分数为有限的非负 double，结果按 `finalScore` 降序，再按 `path`、
`ordinal`、`childId` 升序稳定排序。

## Chunking

`CodebaseIngestionService` 接受仓库根目录和精确 `repositoryId`，递归读取 UTF-8 常规文件，
跳过 `.git`、`target`、`node_modules` 和二进制文件。Java 文件使用 `AstService`：每个顶层
和嵌套类生成一个 ParentChunk，直接声明的方法生成 ChildChunk；没有方法的类生成一个覆盖
类源码的 ChildChunk。非 Java 文件按 120 行窗口切分，窗口重叠 20 行，形成一个 ParentChunk
和多个 ChildChunk。文件读取或 AST 解析失败会停止本次 ingest，并保留原始 cause。

一次 ingest 对 repositoryId 执行同一事务内的 replace：先删除旧 child/parent，再写入新
父子块和 embedding；任何失败整体回滚，不产生半套索引。

## PostgreSQL Schema

`agent-rag/src/main/resources/db/migration/V1__create_rag_tables.sql` 精确执行：

```sql
create extension if not exists vector;

create table rag_parent_chunks (
    parent_id uuid primary key,
    repository_id varchar(255) not null,
    path text not null,
    symbol text,
    content text not null,
    start_line integer not null check (start_line > 0),
    end_line integer not null check (end_line >= start_line),
    metadata_json jsonb not null,
    created_at timestamptz not null
);

create table rag_child_chunks (
    child_id uuid primary key,
    parent_id uuid not null references rag_parent_chunks(parent_id) on delete cascade,
    repository_id varchar(255) not null,
    path text not null,
    symbol text,
    ordinal integer not null check (ordinal >= 0),
    content text not null,
    start_line integer not null check (start_line > 0),
    end_line integer not null check (end_line >= start_line),
    embedding vector(8) not null,
    search_vector tsvector generated always as
        (to_tsvector('simple', content)) stored,
    created_at timestamptz not null,
    unique (parent_id, ordinal)
);

create index idx_rag_child_repository on rag_child_chunks(repository_id);
create index idx_rag_child_search_vector on rag_child_chunks using gin(search_vector);
create index idx_rag_child_embedding on rag_child_chunks
    using hnsw (embedding vector_cosine_ops);
```

The migration is also executable directly by integration tests. The application does not silently
fall back when `vector` is unavailable; schema setup fails with the original database exception.

## Retrieval

`JdbcRagStore` exposes insert/replace and retrieval queries. Vector retrieval uses
`embedding <=> cast(? as vector)` and return `1 - distance` as `vectorScore`. Lexical retrieval
use `search_vector @@ websearch_to_tsquery('simple', ?)` and return the stored content and
PostgreSQL document statistics needed by `Bm25Scorer`.

`Bm25Scorer` tokenizes with lowercase Unicode letter/digit runs, uses `k1 = 1.2` and `b = 0.75`,
and computes the standard BM25 score from term frequency, document frequency, document length and
average length. It never executes SQL string concatenation; query values are bound parameters.

`HybridRagRetriever` unions vector and lexical retrieval rows by exact `childId`. `symbolScore` is 1
when the query contains the exact stored symbol or path segment, otherwise 0. Scores are min-max
normalized independently for `vectorScore` and `bm25Score` across the merged retrieval set (an
all-equal set becomes 0); `symbolScore` remains the exact value 0 or 1. The three scores are then
combined as:

```text
finalScore = 0.55 * vectorScore + 0.30 * bm25Score + 0.15 * symbolScore
```

The retriever returns at most `limit` hits and rejects retrieval rows from another repositoryId.
Missing vector or lexical rows are valid and score as zero; database failures and invalid
rows raise `RagStoreException` with the original cause.

## Error and lifecycle semantics

Records are immutable. Store and retriever are not global singletons; callers inject a `DataSource`,
`Clock`, `EmbeddingModel` and `AstService`. `CodebaseIngestionService` and `JdbcRagStore` do not
own or close the caller's DataSource. Every transaction is closed by Spring JDBC callbacks.

## Test Gates

- Pure unit tests cover Java class/method chunking, non-Java windows, invalid paths, BM25 exact
  scores, normalization, deterministic tie ordering and embedding dimension failures.
- A real PostgreSQL Testcontainers test uses `pgvector/pgvector:pg16`, applies the exact migration,
  ingests a Java fixture, verifies parent/child rows, vector and GIN indexes, repository isolation,
  replacement rollback and hybrid ranking.
- Docker unavailable environments use an explicit JUnit assumption for the integration class only;
  ordinary unit tests must still pass.
- Phase 6.1 completion requires `mvn clean verify` on JDK 21 and `git diff --check`.
