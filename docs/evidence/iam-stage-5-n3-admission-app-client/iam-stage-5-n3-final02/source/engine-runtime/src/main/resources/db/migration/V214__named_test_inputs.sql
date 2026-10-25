-- §30.4.2: draft is an HTTP-resolved foreign identity; no cross-domain FK/schema access.
CREATE TABLE engine.debug_input (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 tenant_id varchar(128) NOT NULL, app_draft_id bigint NOT NULL CHECK(app_draft_id>0),app_code varchar(64) NOT NULL,
 name varchar(128) NOT NULL CHECK(length(btrim(name))>0),inputs jsonb NOT NULL CHECK(jsonb_typeof(inputs)='object'),
 revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),created_by varchar(128) NOT NULL,updated_by varchar(128) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX debug_input_owner_draft ON engine.debug_input(tenant_id,created_by,app_draft_id,id);
-- Durable intent is committed before remote HTTP. Do not cascade-delete it with editable inputs:
-- the monitor ACK can be lost and a later retry must use exactly this frozen source/target.
CREATE TABLE engine.debug_input_promotion (
 tenant_id varchar(128) NOT NULL,created_by varchar(128) NOT NULL,request_id varchar(128) NOT NULL,
 input_id bigint NOT NULL,app_draft_id bigint NOT NULL,app_code varchar(64) NOT NULL,
 request_sha256 char(64) NOT NULL,payload jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,created_by,request_id)
);
