-- The row update lock serializes all policy changes; revision and policy always commit together.
ALTER TABLE app.integration_credential ADD COLUMN policy_revision bigint NOT NULL DEFAULT 1 CHECK (policy_revision > 0);
