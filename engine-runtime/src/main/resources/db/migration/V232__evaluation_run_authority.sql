ALTER TABLE engine.run_authority
 ADD COLUMN candidate_rev bigint,
 ALTER COLUMN app_version_id DROP NOT NULL,
 ALTER COLUMN app_version_no DROP NOT NULL,
 DROP CONSTRAINT run_authority_root_kind_check,
 ADD CONSTRAINT run_authority_root_type CHECK (
   (root_kind='app-version' AND app_version_id IS NOT NULL AND app_version_no IS NOT NULL AND candidate_rev IS NULL)
   OR
   (root_kind='evaluation-candidate' AND app_version_id IS NULL AND app_version_no IS NULL
    AND candidate_rev IS NOT NULL AND candidate_rev>0)
 );
