-- Tool approval is an engine authority bound to the registered immutable call arguments.
-- It is independent of an attempt/lease so a new owner can resume the same logical call safely.
ALTER TABLE engine.tool_call ADD COLUMN approval_context jsonb;
ALTER TABLE engine.node_instance ADD COLUMN approval_context jsonb;
