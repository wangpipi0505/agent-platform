CREATE TABLE model.quota_config (
 tenant_id varchar(128) NOT NULL,provider_id bigint NOT NULL REFERENCES model.provider(id),
 app_code varchar(64) NOT NULL DEFAULT '',user_principal varchar(128) NOT NULL DEFAULT '',
 tpm integer NOT NULL CHECK(tpm>0),rpm integer NOT NULL CHECK(rpm>0),
 daily_tokens integer NOT NULL CHECK(daily_tokens>0),user_concurrency integer NOT NULL CHECK(user_concurrency>0),
 app_concurrency integer NOT NULL CHECK(app_concurrency>0),updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,provider_id,app_code,user_principal)
);
ALTER TABLE model.provider ADD COLUMN health jsonb NOT NULL DEFAULT '{}';
