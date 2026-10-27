-- PLAT-03/10：语义摘要（semantic digest）与受控证据重绑定。
--
-- 背景：发布门禁过去只比 candidate_sha256，而 sha256 覆盖整个 contract（含 description/title 等展示
-- 文案）。只改一句帮助文案也会让已通过的连接测试/选型验收全部失效，编辑只能为了“回到可发布”而
-- 重跑真实测试；反过来，用旧 sha 伪造的证据也无法被识别。这里把“语义内容”与“展示文案”分开：
--   * semantic_digest 只覆盖契约的语义字段（排除集在 SkillDigest 中唯一定义），展示字段改动不改变它；
--   * sha256 保持原义（整份契约的指纹），用于证明“这份字节没有被换过”。
-- 证据行同时记录两者：门禁要求 sha（同一份草稿字节）+ digest（同一份语义）+ passed 才放行。
--
-- 幂等与回滚：全部语句可重复执行（ADD COLUMN IF NOT EXISTS / WHERE ... IS NULL / CREATE TABLE
-- IF NOT EXISTS）。回填值直接取该版本当时的 sha256，这与换卡前“只比 sha”的行为逐字一致，因此
-- 迁移本身不改变任何既有草稿的发布资格；需要收紧时由发布门禁开关
-- agent.skill.evidence-digest-gate 控制。回滚不删除列与表：历史证据仍然可读，只是门禁退回只比 sha。
ALTER TABLE skill.skill_version ADD COLUMN IF NOT EXISTS semantic_digest char(64);

-- 旧行没有摘要，只能回填。sha256 是该版本发布时已冻结的指纹，拿它作为“当时的语义摘要”是唯一
-- 不引入新信息的选择：不能在这里重算 canonical 契约，因为迁移脚本不可能复现 Java 侧的排除规则。
UPDATE skill.skill_version SET semantic_digest = sha256 WHERE semantic_digest IS NULL;

ALTER TABLE skill.skill_version ALTER COLUMN semantic_digest SET NOT NULL;

ALTER TABLE skill.connection_test ADD COLUMN IF NOT EXISTS semantic_digest char(64);
ALTER TABLE skill.acceptance ADD COLUMN IF NOT EXISTS semantic_digest char(64);

-- 证据行的摘要取被测版本在迁移后的摘要；连接测试/验收都是针对“某一个已落库版本”的记录，
-- 版本摘要一旦确定就不会再变（已发布版本不可变，草稿保存会重算 sha 与 digest，见 SkillCatalog.save）。
UPDATE skill.connection_test c
SET semantic_digest = v.semantic_digest
FROM skill.skill_version v
WHERE c.skill_version_id = v.id AND c.semantic_digest IS NULL;

UPDATE skill.acceptance a
SET semantic_digest = v.semantic_digest
FROM skill.skill_version v
WHERE a.skill_version_id = v.id AND a.semantic_digest IS NULL;

ALTER TABLE skill.connection_test ALTER COLUMN semantic_digest SET NOT NULL;
ALTER TABLE skill.acceptance ALTER COLUMN semantic_digest SET NOT NULL;

-- 重绑定审计：每次把来源版本的通过证据复制到目标草稿都写一行，保留“谁、为什么、从哪个版本”。
-- 不建外键：来源/目标版本在草稿删除时会被清理（SkillCatalog.delete），审计行必须继续存在以证明
-- 曾经发生过这次绑定；UNIQUE(tenant_id,to_version_id) 让并发/重复绑定在数据库层只剩一个赢家。
CREATE TABLE IF NOT EXISTS skill.evidence_rebind (
    id bigint GENERATED ALWAYS AS IDENTITY,
    tenant_id varchar(128) NOT NULL,
    skill_code varchar(64) NOT NULL,
    from_version_id bigint NOT NULL,
    to_version_id bigint NOT NULL,
    semantic_digest char(64) NOT NULL,
    reason text NOT NULL,
    operator varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, to_version_id)
);
