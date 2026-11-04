-- SEM-08：业务目标处置的持久事实。
--
-- 语义：
-- 1) 目标判据来自 run 受理时冻结的 snapshot.config.goals，不接受执行期客户端追加；
--    该快照由发布配置决定，本表只记录“按它核验出的处置”，不改 run 终态。
-- 2) 执行终态（run/node）、业务效果（tool_call 读回）与目标处置（本表）是三类事实；
--    HTTP 200 或 run=SUCCEEDED 都不能直接推导 outcome_state=ACHIEVED。
-- 3) (run_id,goal_id) 唯一，revision 只在处置/读回/下一步发生变化时递增；重复核验不写新行，
--    以支撑“恢复沿同 run/call 幂等、零重复写”。
-- 4) 不新建任务状态权威：恢复仍走原 run/call 状态与 UNKNOWN 对账，本表只追加处置记录。
--
-- 回滚：
--   drop table if exists engine.goal_outcome;

create table if not exists engine.goal_outcome (
    run_id bigint not null references engine.run (id) on delete cascade,
    goal_id varchar(64) not null,
    criterion jsonb not null,
    outcome_state varchar(16) not null
        check (outcome_state in ('ACHIEVED', 'NOT_ACHIEVED', 'PENDING', 'FAILED', 'UNKNOWN')),
    readback_ref jsonb,
    next_step varchar(256),
    detail jsonb not null default '{}'::jsonb,
    revision int not null default 1 check (revision > 0),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (run_id, goal_id)
);

create index if not exists goal_outcome_state_idx
    on engine.goal_outcome (run_id, outcome_state);

-- 读回引用若存在必须是 call 证据（goal@1.0：ACHIEVED 时必填）。
alter table engine.goal_outcome drop constraint if exists goal_outcome_readback_shape;
alter table engine.goal_outcome
    add constraint goal_outcome_readback_shape
    check (readback_ref is null
        or (jsonb_typeof(readback_ref) = 'object'
            and readback_ref->>'evidenceKind' = 'call'
            and readback_ref ? 'callId'));

alter table engine.goal_outcome drop constraint if exists goal_outcome_achieved_readback;
alter table engine.goal_outcome
    add constraint goal_outcome_achieved_readback
    check (outcome_state <> 'ACHIEVED' or readback_ref is not null);
