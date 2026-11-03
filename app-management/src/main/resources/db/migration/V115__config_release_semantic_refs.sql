-- SEM-02.2 阶段2：配置发布钉住语义绑定的精确修订。
--
-- 边界（对齐 contracts/semantic-runtime.md §2.2 bindingRef 与 §5.1 撤权优先）：
-- 1) 这里只存"发布 -> bindingId + bindingRev"的引用，不复制扩展/技能声明本身；声明内容
--    永远从 app.semantic_binding_revision 的不可变修订读回，避免两份真值互相漂移。
-- 2) 引用在创建发布时冻结；之后绑定新增修订不改旧发布的可回查结果（旧修订行永久保留）。
-- 3) 撤权（绑定 DISABLED）不删除本表行：历史发布仍能反查自己当时钉住的修订，但激活与
--    运行投影都必须先看父行当前状态，撤权优先于任何历史快照。
-- 4) 同一发布内 bindingId 唯一，ref_index 保持创建时的声明顺序，供稳定回显。

CREATE TABLE app.integration_config_release_semantic_ref (
 tenant_id varchar(128) NOT NULL,
 release_id varchar(64) NOT NULL,
 ref_index integer NOT NULL CHECK(ref_index >= 0),
 binding_id varchar(64) NOT NULL,
 binding_rev integer NOT NULL CHECK(binding_rev > 0),
 PRIMARY KEY(tenant_id, release_id, ref_index),
 UNIQUE(tenant_id, release_id, binding_id),
 FOREIGN KEY(tenant_id, release_id)
   REFERENCES app.integration_config_release(tenant_id, release_id),
 FOREIGN KEY(tenant_id, binding_id, binding_rev)
   REFERENCES app.semantic_binding_revision(tenant_id, binding_id, binding_rev)
);

CREATE INDEX config_release_semantic_ref_binding
  ON app.integration_config_release_semantic_ref(tenant_id, binding_id);

-- 手写回滚（Flyway 社区版无 undo）：
-- DROP INDEX app.config_release_semantic_ref_binding;
-- DROP TABLE app.integration_config_release_semantic_ref;
