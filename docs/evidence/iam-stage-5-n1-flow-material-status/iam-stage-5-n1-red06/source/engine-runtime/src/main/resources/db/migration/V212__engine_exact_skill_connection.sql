ALTER TABLE engine.run ADD COLUMN controlled_skill_test_version_id bigint;
ALTER TABLE engine.run ADD COLUMN controlled_skill_test_digest char(64);
ALTER TABLE engine.run ADD COLUMN controlled_skill_test_status varchar(16);
UPDATE engine.run SET controlled_skill_test_version_id=controlled_draft_skill_version_id,
 controlled_skill_test_digest=controlled_draft_skill_digest,controlled_skill_test_status='DRAFT'
 WHERE controlled_draft_skill_version_id IS NOT NULL;
ALTER TABLE engine.run ADD CONSTRAINT controlled_skill_test_debug_only CHECK (
 (controlled_skill_test_version_id IS NULL AND controlled_skill_test_digest IS NULL AND controlled_skill_test_status IS NULL)
 OR (trigger_type='DEBUG' AND controlled_skill_test_version_id IS NOT NULL AND controlled_skill_test_digest IS NOT NULL AND controlled_skill_test_status IN ('DRAFT','PUBLISHED','ARCHIVED'))
);
