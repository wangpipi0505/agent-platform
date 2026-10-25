-- 非秘密模型配置内容寻址；凭据不复制，始终经权威供应商实时解析。
CREATE TABLE model.binding_snapshot (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 tenant_id varchar(128) NOT NULL,
 model_entry_id bigint NOT NULL REFERENCES model.entry(id),
 provider_id bigint NOT NULL REFERENCES model.provider(id),
 sha256 varchar(64) NOT NULL, snapshot jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,sha256)
);
