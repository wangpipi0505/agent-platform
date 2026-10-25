-- Run dispatch is an opportunity to advance work, not a replacement node execution lease.
ALTER TABLE engine.run ADD COLUMN dispatch_next_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE engine.run ADD COLUMN dispatch_owner varchar(64);
ALTER TABLE engine.run ADD COLUMN dispatch_until timestamptz;
ALTER TABLE engine.run ADD COLUMN dispatch_generation bigint NOT NULL DEFAULT 0 CHECK(dispatch_generation>=0);
ALTER TABLE engine.run ADD CONSTRAINT run_dispatch_pair CHECK ((dispatch_owner IS NULL)=(dispatch_until IS NULL));
CREATE INDEX run_dispatch_interactive ON engine.run(dispatch_next_at,id)
 WHERE status IN ('PENDING','RUNNING') AND trigger_type IN ('CHAT','TASK','DEBUG');
CREATE INDEX run_dispatch_batch ON engine.run(dispatch_next_at,id)
 WHERE status IN ('PENDING','RUNNING') AND trigger_type='EVALUATION';
