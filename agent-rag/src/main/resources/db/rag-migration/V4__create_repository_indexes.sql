create table if not exists rag_repository_indexes (
    repository_id varchar(255) primary key,
    workspace_fingerprint varchar(64) not null
        check (workspace_fingerprint ~ '^[0-9a-f]{64}$'),
    parent_count integer not null check (parent_count >= 0),
    child_count integer not null check (child_count >= 0),
    indexed_at timestamptz not null
);
