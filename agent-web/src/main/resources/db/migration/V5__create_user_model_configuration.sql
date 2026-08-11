create table agent_model_providers (
    provider_id uuid primary key,
    owner_user_id varchar(255) not null references agent_users(user_id),
    display_name varchar(255) not null,
    base_url text not null,
    api_key text not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    unique (owner_user_id, display_name)
);

create table agent_model_endpoints (
    endpoint_id uuid primary key,
    provider_id uuid not null references agent_model_providers(provider_id) on delete cascade,
    display_name varchar(255) not null,
    model_id varchar(255) not null,
    capabilities text[] not null,
    priority integer not null check (priority >= 0),
    weight integer not null check (weight > 0),
    enabled boolean not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    unique (provider_id, model_id)
);

create table agent_model_groups (
    group_id uuid primary key,
    owner_user_id varchar(255) not null references agent_users(user_id),
    display_name varchar(255) not null,
    task_type varchar(64) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    unique (owner_user_id, display_name),
    constraint agent_model_groups_task_type_check check (
        task_type in ('CODE', 'VISION', 'QUICK_CLASSIFICATION')
    )
);

create table agent_model_group_endpoints (
    group_id uuid not null references agent_model_groups(group_id) on delete cascade,
    endpoint_id uuid not null references agent_model_endpoints(endpoint_id) on delete cascade,
    position integer not null check (position >= 0),
    primary key (group_id, endpoint_id),
    unique (group_id, position)
);

create index idx_agent_model_providers_owner on agent_model_providers(owner_user_id);
create index idx_agent_model_groups_owner on agent_model_groups(owner_user_id);
