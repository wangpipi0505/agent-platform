-- INT-02 子步3：把"已批准的连接引用"钉在调用记录上。
--
-- 语义：
-- 1) binding_refs 由引擎在登记调用时写入，来源只有运行快照（服务端权威），浏览器与模型参数
--    都不能覆盖它——否则"谁连了哪个端点"就变成调用方自证。
-- 2) 与 args_digest 同级：一次逻辑调用（idempotency_key 唯一）的绑定引用不允许在重试、恢复或
--    受控重放时被改写，否则同一调用记录会先后指向两套连接，审计上无法解释。
-- 3) 缺省语义：存量调用与该批次之前的历史行保持 null，读侧按"未引用受控连接"处理，
--    走原有 legacy 路径，不做回填、不改语义。
--
-- 回滚：
--   alter table engine.tool_call drop constraint if exists tool_call_binding_refs_array;
--   alter table engine.tool_call drop column if exists binding_refs;
alter table engine.tool_call add column if not exists binding_refs jsonb;

alter table engine.tool_call drop constraint if exists tool_call_binding_refs_array;
alter table engine.tool_call
    add constraint tool_call_binding_refs_array
    check (binding_refs is null or jsonb_typeof(binding_refs) = 'array');
