-- IMPORT_SKILLS只产生真实草稿与来源收据；原技能验证/发布门不因新增步骤而放宽。
alter table app.onboarding_session_step drop constraint onboarding_session_step_step_check;
alter table app.onboarding_session_step add constraint onboarding_session_step_step_check
    check(step in ('IMPORT_SKILLS','ASSEMBLE','VERIFY','PUBLISH'));
