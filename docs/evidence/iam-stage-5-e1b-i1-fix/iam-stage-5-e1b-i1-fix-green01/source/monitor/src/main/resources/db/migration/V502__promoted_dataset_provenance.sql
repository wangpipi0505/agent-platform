-- Reuse existing immutable dataset content. Provenance is separate from scoring cases.
ALTER TABLE monitor.dataset ADD COLUMN promoted_by varchar(128),ADD COLUMN promotion_request_id varchar(128),ADD COLUMN promotion_sha256 char(64),ADD COLUMN source jsonb;
ALTER TABLE monitor.dataset ADD CONSTRAINT dataset_promotion_complete CHECK(
 (promoted_by IS NULL AND promotion_request_id IS NULL AND promotion_sha256 IS NULL AND source IS NULL)
 OR (promoted_by IS NOT NULL AND promotion_request_id IS NOT NULL AND promotion_sha256 IS NOT NULL AND source IS NOT NULL)
);
CREATE UNIQUE INDEX dataset_promotion_request ON monitor.dataset(tenant_id,promoted_by,promotion_request_id) WHERE promotion_request_id IS NOT NULL;
