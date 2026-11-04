-- 用户取消理由单独持久化；不改写已应用会话迁移或步骤回执。
alter table app.onboarding_session add column cancel_reason varchar(512);
