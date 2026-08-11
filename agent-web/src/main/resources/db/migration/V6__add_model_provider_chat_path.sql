alter table agent_model_providers
    add column chat_completions_path text not null default '/v1/chat/completions';

alter table agent_model_providers
    add constraint agent_model_providers_chat_path_check
    check (chat_completions_path like '/%');
