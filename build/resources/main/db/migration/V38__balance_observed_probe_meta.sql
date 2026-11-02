-- Monotonic / quality-aware cold observed writes: remember last probe quality + time
-- so OPTIMISTIC_DELTA cannot clobber a fresh LIVE_MEMPOOL_AWARE total.

ALTER TABLE financial.balances_core
    ADD COLUMN IF NOT EXISTS observed_probe_quality VARCHAR(32),
    ADD COLUMN IF NOT EXISTS observed_probe_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS observed_probe_source VARCHAR(96);
