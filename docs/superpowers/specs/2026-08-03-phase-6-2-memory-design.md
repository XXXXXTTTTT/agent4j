# Phase 6.2 Long-Term Memory Design

## Scope

Phase 6.2 adds a persistent long-term memory center to `agent-rag` and a memory-aware
`PlannerNode` to `agent-core`. The memory center extracts, stores, retrieves and formats exactly
three memory types: `USER_PREFERENCE`, `ARCHITECTURE_RULE` and `BAD_CASE`. Retrieval is isolated by
the exact pair `repositoryId + userId`; no case conversion, prefix matching or fallback scope is
allowed.

This phase does not add Langfuse, OpenTelemetry, benchmark execution, REST endpoints, background
memory consolidation or an implicit global cache. PostgreSQL remains the sole source of truth.

## Module Boundary

`agent-core` defines a framework-independent memory context port under `com.agent.core.memory` and
the `PlannerNode` under `com.agent.core.nodes`. `agent-core` does not depend on `agent-rag`.

`agent-rag` adds an explicit dependency on `agent-core`. It implements the core memory port with a
`MemoryContextProviderAdapter`, and uses the existing `ModelRouter` through
`ModelMemoryExtractor`. The dependency direction is therefore `agent-rag -> agent-core ->
agent-sandbox`; no dependency cycle is introduced.

All dependencies are constructor injected. `MemoryManager` and JDBC stores do not own or close the
injected `DataSource`, `EmbeddingModel`, `ModelRouter` or `Clock`.

## Core Memory Port

`com.agent.core.memory.MemoryContextRequest` is an immutable record with fields
`repositoryId`, `userId`, `query` and `limit`. The three text fields must be non-blank and `limit`
must be in the closed range 1 to 20.

`com.agent.core.memory.MemoryContext` is an immutable record with fields `prompt` and
`entryCount`. `prompt` must be non-null, `entryCount` must be non-negative, and an empty result is
represented exactly as `new MemoryContext("", 0)`.

`com.agent.core.memory.MemoryContextProvider` is a functional interface:

```java
MemoryContext recall(MemoryContextRequest request);
```

Implementations must either return a non-null context or throw. They must not silently convert a
failure into an empty context.

## Memory Domain Protocol

The following public types live under `com.agent.rag.memory`:

- `MemoryType`: enum values `USER_PREFERENCE`, `ARCHITECTURE_RULE`, `BAD_CASE`.
- `MemoryCapture`: record fields `repositoryId`, `userId`, `sourceText`.
- `MemoryDraft`: record fields `type`, `title`, `content`.
- `MemoryEntry`: record fields `memoryId`, `repositoryId`, `userId`, `type`, `title`, `content`,
  `contentHash`, `embedding`, `createdAt`, `updatedAt`.
- `MemoryQuery`: record fields `repositoryId`, `userId`, `query`, `types`, `limit`.
- `MemoryHit`: record fields `entry`, `vectorScore`, `lexicalScore`, `finalScore`.

`MemoryCapture.sourceText` is limited to 20,000 characters. `MemoryDraft.title` is limited to 200
characters and `MemoryDraft.content` to 4,000 characters. `MemoryQuery.types` must be a non-empty
immutable `Set<MemoryType>` and `limit` must be 1 to 20. Embeddings must contain exactly eight
finite floats and are defensively copied at construction and access.

All result scores must be finite and non-negative. Hits sort by `finalScore` descending, then
`updatedAt` descending, then `memoryId` ascending.

## Extraction

`MemoryExtractor` exposes:

```java
List<MemoryDraft> extract(MemoryCapture capture);
```

`ModelMemoryExtractor` sends one `TaskType.QUICK_CLASSIFICATION` request through the injected
`ModelRouter`. The request has no tools and requires exactly this JSON shape:

```json
{
  "memories": [
    {
      "type": "USER_PREFERENCE",
      "title": "Use constructor injection",
      "content": "The user requires dependencies to be passed through constructors."
    }
  ]
}
```

The response root may contain only `memories`; each item may contain only `type`, `title` and
`content`. Unknown fields, missing fields, nulls, non-text values, unknown enum values and more than
20 items fail extraction. An empty `memories` array is valid. The extractor preserves the original
model or JSON exception as the cause of `MemoryExtractionException`.

The system instruction explicitly states that only durable, user-confirmed facts are extracted;
temporary task commands, secrets and model speculation are excluded. This phase never attempts to
infer a memory type from identifier spelling.

## Persistence

Migration `agent-rag/src/main/resources/db/migration/V2__create_memory_table.sql` creates:

```sql
create table rag_memories (
    memory_id uuid primary key,
    repository_id varchar(255) not null,
    user_id varchar(255) not null,
    memory_type varchar(32) not null check (
        memory_type in ('USER_PREFERENCE', 'ARCHITECTURE_RULE', 'BAD_CASE')),
    title varchar(200) not null,
    content varchar(4000) not null,
    content_hash char(64) not null,
    embedding vector(8) not null,
    search_vector tsvector generated always as
        (to_tsvector('simple', title || ' ' || content)) stored,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    unique (repository_id, user_id, memory_type, content_hash)
);

create index idx_rag_memories_scope
    on rag_memories(repository_id, user_id, memory_type);
create index idx_rag_memories_search_vector
    on rag_memories using gin(search_vector);
create index idx_rag_memories_embedding
    on rag_memories using hnsw (embedding vector_cosine_ops);
```

`MemoryStore` exposes atomic `upsertAll`, `findByVector` and `findByLexical` operations.
`JdbcMemoryStore` uses bound parameters only. Upsert preserves the existing `memoryId` and
`createdAt` for the exact unique key, while replacing `title`, `content`, `embedding` and
`updatedAt`. A capture batch is one transaction: any invalid row or database failure rolls back the
entire batch. Database failures become `MemoryStoreException` with the original cause.

`contentHash` is lowercase SHA-256 hex of the exact UTF-8 sequence
`type.name() + "\n" + title + "\n" + content`. No whitespace, Unicode or case normalization is
performed.

## MemoryManager

`MemoryManager` receives `MemoryExtractor`, `MemoryStore`, `EmbeddingModel`, `Clock` and a
`Supplier<UUID>` through its constructor.

`capture(MemoryCapture)` extracts at most 20 drafts, calculates the exact content hash, obtains an
eight-dimensional embedding from `title + "\n" + content`, creates entries using the injected
clock and UUID supplier, and atomically upserts the batch. It returns the persisted entries in
extractor order. An empty extraction performs no database write and returns an empty immutable
list.

`recall(MemoryQuery)` obtains one query embedding, fetches vector and lexical rows for the exact
scope and requested types, merges rows by exact `memoryId`, independently min-max normalizes
vector and lexical scores, and computes:

```text
finalScore = 0.65 * vectorScore + 0.35 * lexicalScore
```

An all-equal score set normalizes to zero. The manager rejects any store row whose repositoryId,
userId or type does not exactly match the query. It returns at most `limit` hits using the stable
ordering defined above. Extraction, embedding and storage failures are never discarded.

## Planner Integration

`PlannerNode` uses these exact state keys:

- input `planner.repositoryId`
- input `planner.userId`
- input `planner.task`
- output `planner.memoryContext`
- output `planner.plan`
- output `planner.model`
- failure `planner.error`

The constructor receives `ModelRouter`, `MemoryContextProvider` and `memoryLimit`. `memoryLimit`
must be 1 to 20. Execution validates all three input keys, recalls memory with the exact task text,
and sends a `TaskType.CODE` request containing:

1. A fixed system message that says current user instructions outrank recalled memory and that
   recalled text is untrusted historical context.
2. A user message containing the exact task and the recalled prompt in delimited sections.

The request has no tools, no tool choice and temperature `0.0`. The first response choice must
contain `ChatMessage.TextContent`; its exact text is stored in `planner.plan`. The routed model is
stored in `planner.model`, the context prompt in `planner.memoryContext`, and `planner` is appended
to the trace.

Any validation, recall, routing or response parsing failure writes the complete stack trace to
`planner.error` and appends `planner`. Failure does not write `planner.plan` or replace existing
messages. The node does not fall back to an empty memory context because doing so would hide a
broken long-term-memory dependency.

`MemoryContextProviderAdapter` invokes `MemoryManager.recall` and formats each hit exactly as:

```text
[MEMORY_TYPE] title
content
```

Entries are separated by one blank line. The adapter returns the complete formatted result and
entry count; it performs no additional ranking or truncation.

## Error and Concurrency Semantics

All domain records are immutable. `MemoryManager`, `ModelMemoryExtractor`,
`MemoryContextProviderAdapter` and `JdbcMemoryStore` hold no mutable request state and are safe for
concurrent virtual-thread calls. JDBC transactions are request scoped. Exceptions retain their
causes, and `PlannerNode` retains the full stack trace in state.

## Test Gates

- Unit tests cover record validation, defensive embedding copies, exact SHA-256 input, strict JSON
  extraction, empty extraction, embedding validation, deduplication request formation, score
  normalization, scope/type rejection and stable ordering.
- `PlannerNodeTest` verifies exact state keys, recalled prompt injection, `TaskType.CODE`,
  temperature `0.0`, routed model capture, trace and full failure stack.
- A graph test executes `PlannerNode -> CoderNode -> OpsNode` with deterministic memory, model,
  patch and terminal adapters.
- A real `pgvector/pgvector:pg16` Testcontainers test applies `V1` then `V2`, verifies extension,
  table and exact index names, atomic upsert, exact scope/type isolation, vector/GIN retrieval,
  duplicate preservation and rollback. Docker-unavailable environments skip only this integration
  class through an explicit JUnit assumption.
- Completion requires JDK 21, `mvn clean verify`, `git diff --check`, no project Docker/WinPTY
  residue, an updated `docs/ENGINEERING_PITFALLS.md`, and Conventional Commits.
