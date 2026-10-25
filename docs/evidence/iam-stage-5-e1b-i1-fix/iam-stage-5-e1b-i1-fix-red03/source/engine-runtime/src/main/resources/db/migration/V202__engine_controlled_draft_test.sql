-- Only the server-created skill acceptance path may bind a draft version to a DEBUG run.
ALTER TABLE engine.run ADD COLUMN controlled_draft_skill_version_id bigint;
ALTER TABLE engine.run ADD COLUMN controlled_draft_skill_digest char(64);
ALTER TABLE engine.run ADD CONSTRAINT controlled_draft_debug_only CHECK (
 (controlled_draft_skill_version_id IS NULL AND controlled_draft_skill_digest IS NULL)
 OR (trigger_type='DEBUG' AND controlled_draft_skill_version_id IS NOT NULL AND controlled_draft_skill_digest IS NOT NULL)
);
