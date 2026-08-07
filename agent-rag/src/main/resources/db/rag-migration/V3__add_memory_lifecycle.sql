alter table rag_memories
    add column importance double precision not null default 0.5
        check (importance >= 0.0 and importance <= 1.0),
    add column access_count bigint not null default 0
        check (access_count >= 0),
    add column last_accessed_at timestamptz;

update rag_memories
set last_accessed_at = updated_at;

alter table rag_memories
    alter column last_accessed_at set not null,
    alter column last_accessed_at set default current_timestamp;

create index idx_rag_memories_lifecycle
    on rag_memories(repository_id, user_id, memory_type, updated_at desc);
