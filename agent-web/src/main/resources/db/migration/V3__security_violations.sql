create table agent_security_violations (
    violation_id uuid primary key,
    run_id uuid not null,
    user_id varchar(128) not null,
    node_name varchar(128) not null,
    tool_name varchar(128),
    violation_type varchar(32) not null,
    severity varchar(16) not null,
    rule_id varchar(128) not null,
    summary varchar(512) not null,
    occurred_at timestamp(6) with time zone not null
);

create index idx_security_violations_run
    on agent_security_violations (run_id, occurred_at);

create index idx_security_violations_user
    on agent_security_violations (user_id, occurred_at);
