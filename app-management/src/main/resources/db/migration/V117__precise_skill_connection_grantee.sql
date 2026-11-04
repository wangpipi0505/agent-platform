-- 保留历史目录主体；新增执行协议精确覆盖既有SkillCatalog完整code域（最长64＋skill.共70）。
alter table app.connection_profile_grant alter column grantee type varchar(70);
alter table app.connection_profile_grant drop constraint connection_profile_grant_grantee_format;
alter table app.connection_profile_grant add constraint connection_profile_grant_grantee_format
    check (grantee ~ '^[a-z][a-z0-9._-]{2,63}$' or grantee ~ '^skill\.[A-Za-z][A-Za-z0-9_-]{0,63}$');
