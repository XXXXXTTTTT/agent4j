create table if not exists rag_memories (
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

create index if not exists idx_rag_memories_scope
    on rag_memories(repository_id, user_id, memory_type);
create index if not exists idx_rag_memories_search_vector
    on rag_memories using gin(search_vector);
create index if not exists idx_rag_memories_embedding
    on rag_memories using hnsw (embedding vector_cosine_ops);
