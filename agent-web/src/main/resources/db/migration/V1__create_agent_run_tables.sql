create table agent_runs (
    run_id uuid primary key,
    graph_id varchar(255) not null,
    status varchar(32) not null,
    latest_version bigint not null check (latest_version >= 0),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint agent_runs_status_check check (
        status in ('RUNNING', 'WAITING_APPROVAL', 'COMPLETED', 'REJECTED', 'FAILED')
    )
);

create table agent_checkpoints (
    run_id uuid not null references agent_runs(run_id),
    version bigint not null check (version >= 0),
    graph_id varchar(255) not null,
    status varchar(32) not null,
    state_json jsonb not null,
    next_node varchar(255),
    interrupt_json jsonb,
    approval_decision varchar(16),
    approval_reason text,
    error text,
    created_at timestamptz not null,
    primary key (run_id, version),
    constraint agent_checkpoints_status_check check (
        status in ('RUNNING', 'WAITING_APPROVAL', 'COMPLETED', 'REJECTED', 'FAILED')
    ),
    constraint agent_checkpoints_approval_check check (
        approval_decision is null or approval_decision in ('APPROVE', 'REJECT')
    )
);

create index idx_agent_runs_status_updated_at
    on agent_runs(status, updated_at);

create index idx_agent_checkpoints_run_version_desc
    on agent_checkpoints(run_id, version desc);
