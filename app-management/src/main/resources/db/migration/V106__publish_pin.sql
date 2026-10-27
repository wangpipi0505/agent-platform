-- T18.3 / PLAT-18：发布绑定（pin）结果落库，使"某应用当前 pinned 到哪个 release"可从平台库反查，
-- 不再依赖本体绑定点或发布日志。
-- 迁移顺序：本迁移与 V107 同批分先后入库，Fresh DB 从 105→106→107 顺序应用；任何已先应用到 V107
-- 的存量库再遇本迁移会触发 Flyway outOfOrder 校验失败，需按 outOfOrder 一次性补齐（仅限既有
-- 测试/演示库的运维修复），Fresh DB 无此问题。
-- 选型（方案二选一，取 B）：新建 app.publish_pin 而不是在 app.publish_report.checks JSON 内补列——
-- pin 生命周期独立于单次发布报告（报告是 15 分钟有效期的预检证据，pin 是应用级当前状态；
-- DISABLED/SKIPPED 与任何一次报告都无关），且"按应用反查当前绑定"需要应用粒度的唯一行做 O(1) 命中。
-- 语义（定死，UPSERT）：每应用只保留一行"最近一次发布尝试"的 pin 结果（UNIQUE(tenant_id,app_code)），
-- 重新发布整行覆盖；last_pinned_* 三列只在 state='PINNED' 时推进——最后一次失败/跳过不抹掉
-- "远端当前仍指向哪个绑定"的已知事实（fail-open 语义下远端绑定未因失败而改变）。
-- release_id 是本次尝试声明的目标 release（来自候选 config.ontology，尽力记录，非远端回执）；
-- binding_id 只在远端 2xx 回执里出现。写入点在发布事务提交之后（与返回值组装同一处），本表不参与发布事务。
CREATE TABLE app.publish_pin (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 tenant_id varchar(128) NOT NULL,
 app_code varchar(64) NOT NULL,
 app_version_no int NOT NULL CHECK (app_version_no > 0),
 state varchar(16) NOT NULL CHECK (state IN ('DISABLED','SKIPPED','PINNED','FAILED')),
 release_id varchar(64),
 binding_id varchar(128),
 reason varchar(200),
 environment varchar(64) NOT NULL DEFAULT 'production',
 last_pinned_release_id varchar(64),
 last_pinned_binding_id varchar(128),
 last_pinned_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT publish_pin_app_fk FOREIGN KEY (tenant_id, app_code) REFERENCES app.application(tenant_id, code),
 CONSTRAINT publish_pin_current UNIQUE (tenant_id, app_code)
);

-- 回滚（down，需手动执行；drop 前确认无在途发布写入窗口）：
-- DROP TABLE IF EXISTS app.publish_pin;
