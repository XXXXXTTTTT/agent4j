-- 能力安装的运行治理字段和乐观锁；不改写已发布 V7 历史。
alter table agent_mcp_installations
    add column risk_level varchar(16) not null default 'HIGH',
    add column required_capabilities jsonb not null default '["TOOL"]'::jsonb,
    add column workspace_mount_mode varchar(16) not null default 'NONE',
    add column network_mode varchar(16) not null default 'NONE',
    add column runtime_image text not null default '',
    add column container_id text,
    add column runtime_error text,
    add column version bigint not null default 0,
    add constraint agent_mcp_installations_risk_level_check check (risk_level in ('LOW', 'MEDIUM', 'HIGH')),
    add constraint agent_mcp_installations_workspace_mount_mode_check check (
        workspace_mount_mode in ('NONE', 'READ_ONLY', 'READ_WRITE')
    ),
    add constraint agent_mcp_installations_network_mode_check check (network_mode = 'NONE'),
    add constraint agent_mcp_installations_version_check check (version >= 0);

alter table agent_skill_installations
    add column version bigint not null default 0,
    add constraint agent_skill_installations_version_check check (version >= 0);

create table agent_mcp_tool_bindings (
    installation_id uuid not null references agent_mcp_installations(installation_id) on delete cascade,
    local_tool_name varchar(64) not null,
    remote_tool_name varchar(255) not null,
    created_at timestamptz not null,
    primary key (installation_id, local_tool_name),
    unique (local_tool_name)
);

alter table agent_capability_management_audit
    add column operation_id uuid,
    add column from_status varchar(32),
    add column to_status varchar(32),
    add column detail_sha256 char(64);

create index idx_agent_mcp_installations_status_version
    on agent_mcp_installations (status, version, updated_at desc);
create index idx_agent_skill_installations_status_version
    on agent_skill_installations (status, version, updated_at desc);
