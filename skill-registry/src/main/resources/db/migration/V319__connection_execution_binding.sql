-- CR-02：一次解析回执固定实际出站绑定；不补写历史未知绑定，不保存凭据值。
alter table skill.connection_resolution add column execution_binding jsonb;
