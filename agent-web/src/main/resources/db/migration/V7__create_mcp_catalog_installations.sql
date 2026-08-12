-- 官方目录快照与经确认的能力安装记录；源内容固定在 commit/blob/SHA-256 上。
create table agent_mcp_catalog_snapshots (
    snapshot_id uuid primary key,
    repository varchar(255) not null,
    commit_sha char(40) not null,
    fetched_at timestamptz not null,
    expires_at timestamptz not null,
    etag text,
    status varchar(32) not null,
    servers jsonb not null,
    errors jsonb not null,
    unique (repository, commit_sha)
);

create table agent_mcp_installation_snapshots (
    snapshot_id uuid primary key,
    server_key varchar(255) not null,
    repository_path text not null,
    source_url text not null,
    commit_sha char(40) not null,
    blob_shas jsonb not null,
    metadata_sha256 char(64) not null,
    version varchar(255) not null,
    description text not null,
    license text not null,
    command varchar(255) not null,
    arguments jsonb not null,
    launch_bin varchar(255) not null,
    environment_variable_names jsonb not null,
    readme_summary text not null,
    created_at timestamptz not null,
    unique (server_key, commit_sha, metadata_sha256)
);

create table agent_mcp_installations (
    installation_id uuid primary key,
    snapshot_id uuid not null references agent_mcp_installation_snapshots(snapshot_id),
    scope varchar(16) not null,
    workspace_id uuid references agent_workspaces(workspace_id),
    actor_user_id varchar(255) not null references agent_users(user_id),
    status varchar(32) not null,
    confirmation_token_sha256 char(64) not null,
    created_at timestamptz not null,
    confirmed_at timestamptz not null,
    updated_at timestamptz not null,
    constraint agent_mcp_installations_scope_check check (scope in ('WORKSPACE', 'USER_GLOBAL')),
    constraint agent_mcp_installations_status_check check (
        status in ('PREVIEW', 'PENDING_APPROVAL', 'INSTALLING', 'RUNNING', 'FAILED', 'STOPPING', 'STOPPED', 'REJECTED')
    ),
    constraint agent_mcp_installations_scope_workspace_check check (
        (scope = 'WORKSPACE' and workspace_id is not null) or
        (scope = 'USER_GLOBAL' and workspace_id is null)
    )
);

create index idx_agent_mcp_installations_workspace_actor
    on agent_mcp_installations (workspace_id, actor_user_id, updated_at desc);
create index idx_agent_mcp_installations_actor_scope
    on agent_mcp_installations (actor_user_id, scope, updated_at desc);

create table agent_skill_snapshots (
    skill_snapshot_id uuid primary key,
    repository_url text not null,
    repository text not null,
    commit_sha char(40) not null,
    blob_sha text not null,
    skill_path text not null check (skill_path = 'SKILL.md'),
    license text not null,
    content_sha256 char(64) not null,
    summary text not null,
    requested_tool_names jsonb not null,
    content text not null,
    created_at timestamptz not null,
    unique (repository, commit_sha, blob_sha, skill_path, content_sha256)
);

create table agent_skill_installations (
    skill_installation_id uuid primary key,
    skill_snapshot_id uuid not null references agent_skill_snapshots(skill_snapshot_id),
    scope varchar(16) not null,
    workspace_id uuid references agent_workspaces(workspace_id),
    actor_user_id varchar(255) not null references agent_users(user_id),
    status varchar(32) not null,
    confirmation_token_sha256 char(64) not null,
    created_at timestamptz not null,
    confirmed_at timestamptz,
    updated_at timestamptz not null,
    constraint agent_skill_installations_scope_check check (scope in ('WORKSPACE', 'USER_GLOBAL')),
    constraint agent_skill_installations_status_check check (status in ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'REMOVED')),
    constraint agent_skill_installations_scope_workspace_check check (
        (scope = 'WORKSPACE' and workspace_id is not null) or
        (scope = 'USER_GLOBAL' and workspace_id is null)
    )
);

create index idx_agent_skill_installations_workspace_actor
    on agent_skill_installations (workspace_id, actor_user_id, updated_at desc);
create index idx_agent_skill_installations_actor_scope
    on agent_skill_installations (actor_user_id, scope, updated_at desc);

create table agent_capability_management_audit (
    audit_id uuid primary key,
    event_type varchar(64) not null,
    actor_user_id varchar(255) not null references agent_users(user_id),
    workspace_id uuid references agent_workspaces(workspace_id),
    installation_id uuid,
    skill_id uuid,
    run_id uuid,
    source_commit_sha char(40),
    result varchar(32) not null,
    occurred_at timestamptz not null
);

create index idx_agent_capability_audit_workspace_time
    on agent_capability_management_audit (workspace_id, occurred_at desc);
create index idx_agent_capability_audit_actor_time
    on agent_capability_management_audit (actor_user_id, occurred_at desc);
