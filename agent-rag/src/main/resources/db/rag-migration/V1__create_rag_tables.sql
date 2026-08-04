create extension if not exists vector;

create table if not exists rag_parent_chunks (
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

create table if not exists rag_child_chunks (
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

create index if not exists idx_rag_child_repository on rag_child_chunks(repository_id);
create index if not exists idx_rag_child_search_vector on rag_child_chunks using gin(search_vector);
create index if not exists idx_rag_child_embedding on rag_child_chunks using hnsw (embedding vector_cosine_ops);
