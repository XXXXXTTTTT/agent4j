create table agent_users (
    user_id varchar(255) primary key,
    display_name varchar(255) not null,
    enabled boolean not null,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create table agent_workspaces (
    workspace_id uuid primary key,
    owner_user_id varchar(255) not null references agent_users(user_id),
    display_name varchar(255) not null,
    workspace_path text not null,
    repository_id varchar(255) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    unique (owner_user_id, workspace_path)
);

create table agent_workspace_members (
    workspace_id uuid not null references agent_workspaces(workspace_id),
    user_id varchar(255) not null references agent_users(user_id),
    permission varchar(16) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (workspace_id, user_id),
    constraint agent_workspace_members_permission_check check (
        permission in ('VIEWER', 'OPERATOR', 'OWNER')
    )
);

create table agent_conversations (
    conversation_id uuid primary key,
    workspace_id uuid not null references agent_workspaces(workspace_id),
    created_by varchar(255) not null references agent_users(user_id),
    title varchar(255) not null,
    status varchar(16) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint agent_conversations_status_check check (
        status in ('ACTIVE', 'ARCHIVED')
    )
);

create table agent_conversation_turns (
    turn_id uuid primary key,
    conversation_id uuid not null references agent_conversations(conversation_id),
    turn_index bigint not null check (turn_index >= 1),
    user_content text not null,
    assistant_content text,
    run_id uuid unique references agent_runs(run_id),
    status varchar(16) not null,
    error text,
    created_at timestamptz not null,
    completed_at timestamptz,
    unique (conversation_id, turn_index),
    constraint agent_conversation_turns_status_check check (
        status in ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')
    ),
    constraint agent_conversation_turns_error_check check (
        (status = 'FAILED' and error is not null)
        or (status <> 'FAILED' and error is null)
    )
);

create index idx_agent_workspace_members_user
    on agent_workspace_members(user_id, workspace_id);

create index idx_agent_conversations_workspace_updated
    on agent_conversations(workspace_id, status, updated_at desc);

create index idx_agent_conversation_turns_conversation_index
    on agent_conversation_turns(conversation_id, turn_index);
