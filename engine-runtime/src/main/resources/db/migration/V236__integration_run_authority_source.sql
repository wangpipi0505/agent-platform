ALTER TABLE engine.run_authority
    DROP CONSTRAINT run_authority_source_kind_check,
    ADD CONSTRAINT run_authority_source_kind_check CHECK (
        source_kind = 'PORTAL' OR (source_kind = 'INTEGRATION' AND root_kind = 'app-version')
    );
