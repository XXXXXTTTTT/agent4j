-- MCP 离线物料、工具治理元数据和已确认运行镜像；不改写既有 V8。
alter table agent_mcp_installation_snapshots
    add column material_directory text,
    add column material_sha256 char(64),
    add column material_command varchar(255),
    add column material_arguments jsonb,
    add column material_prepared_at timestamptz,
    add constraint agent_mcp_installation_snapshots_material_complete_check check (
        (material_directory is null and material_sha256 is null and material_command is null
            and material_arguments is null and material_prepared_at is null)
        or
        (material_directory is not null and material_sha256 is not null and material_command is not null
            and material_arguments is not null and material_prepared_at is not null)
    ),
    add constraint agent_mcp_installation_snapshots_material_sha256_check check (
        material_sha256 is null or material_sha256 ~ '^[0-9a-f]{64}$'
    );

delete from agent_mcp_tool_bindings;

alter table agent_mcp_tool_bindings
    add column risk_level varchar(16) not null,
    add column required_capabilities jsonb not null,
    add constraint agent_mcp_tool_bindings_risk_level_check check (risk_level in ('LOW', 'MEDIUM', 'HIGH'));

alter table agent_mcp_installations
    add column runtime_image_confirmed boolean not null default false,
    add column runtime_workspace_id uuid references agent_workspaces(workspace_id);
