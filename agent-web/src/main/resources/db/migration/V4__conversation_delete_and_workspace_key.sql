-- 会话删除保持工作区与工作区级记忆，补齐关联数据的级联约束。
alter table agent_checkpoints
    drop constraint if exists agent_checkpoints_run_id_fkey;

alter table agent_checkpoints
    add constraint agent_checkpoints_run_id_fkey
    foreign key (run_id) references agent_runs(run_id) on delete cascade;

alter table agent_conversation_turns
    drop constraint if exists agent_conversation_turns_conversation_id_fkey;

alter table agent_conversation_turns
    add constraint agent_conversation_turns_conversation_id_fkey
    foreign key (conversation_id) references agent_conversations(conversation_id) on delete cascade;

alter table agent_conversation_turns
    drop constraint if exists agent_conversation_turns_run_id_fkey;

alter table agent_conversation_turns
    add constraint agent_conversation_turns_run_id_fkey
    foreign key (run_id) references agent_runs(run_id) on delete set null;

-- 同一用户、同一路径和仓库标识必须保持独立；历史重复记录保留更新时间最新的一条，
-- 先迁移依赖关系再删除旧记录，避免升级时触发外键约束。
do $$
declare
    duplicate record;
begin
    for duplicate in
        select older.workspace_id as old_id, newer.workspace_id as new_id
        from agent_workspaces older
        join agent_workspaces newer
          on newer.owner_user_id = older.owner_user_id
         and newer.workspace_path = older.workspace_path
         and newer.repository_id = older.repository_id
         and (older.updated_at, older.workspace_id) < (newer.updated_at, newer.workspace_id)
    loop
        update agent_conversations
        set workspace_id = duplicate.new_id
        where workspace_id = duplicate.old_id;

        delete from agent_workspace_members old_member
        where old_member.workspace_id = duplicate.old_id
          and exists (
              select 1 from agent_workspace_members new_member
              where new_member.workspace_id = duplicate.new_id
                and new_member.user_id = old_member.user_id
          );

        update agent_workspace_members
        set workspace_id = duplicate.new_id
        where workspace_id = duplicate.old_id;

        delete from agent_workspaces where workspace_id = duplicate.old_id;
    end loop;
end $$;

alter table agent_workspaces
    drop constraint if exists agent_workspaces_owner_user_id_workspace_path_key;

alter table agent_workspaces
    add constraint agent_workspaces_owner_path_repository_key
    unique (owner_user_id, workspace_path, repository_id);
