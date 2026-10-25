-- Original initial request spelling is a write-once fact. Historic NULL cannot be sent.
ALTER TABLE engine.model_input_snapshot ADD COLUMN canonical_request_utf8 text
 CHECK (canonical_request_utf8 IS NULL OR octet_length(canonical_request_utf8) BETWEEN 1 AND 2097152);
