ALTER TABLE financial.channel_operation_decisions
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);

-- Existing forensic rows predate deterministic command keys. Their decision id is
-- a stable, collision-free fallback until new lifecycle commands use the hash key.
UPDATE financial.channel_operation_decisions
   SET idempotency_key = md5(id::text)
 WHERE idempotency_key IS NULL;

ALTER TABLE financial.channel_operation_decisions
    ALTER COLUMN idempotency_key SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_channel_operation_idempotency
    ON financial.channel_operation_decisions(idempotency_key);
