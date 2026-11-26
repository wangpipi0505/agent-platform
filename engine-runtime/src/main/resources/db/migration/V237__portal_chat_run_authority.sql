ALTER TABLE engine.run_authority DROP CONSTRAINT run_authority_root_type;
ALTER TABLE engine.run_authority ADD CONSTRAINT run_authority_root_type CHECK (
    (root_kind IN ('app-version', 'portal-chat-session') AND app_version_id IS NOT NULL
     AND app_version_no IS NOT NULL AND candidate_rev IS NULL)
    OR (root_kind = 'evaluation-candidate' AND app_version_id IS NULL AND app_version_no IS NULL
        AND candidate_rev IS NOT NULL AND candidate_rev > 0)
);
