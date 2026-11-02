ALTER TABLE financial.financial_execution_outbox
    ADD COLUMN IF NOT EXISTS claim_token UUID,
    ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS prepared_payload_ciphertext TEXT,
    ADD COLUMN IF NOT EXISTS prepared_payload_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS execution_reference VARCHAR(255),
    ADD COLUMN IF NOT EXISTS row_version BIGINT NOT NULL DEFAULT 0;

UPDATE financial.financial_execution_outbox
SET lease_expires_at = COALESCE(claimed_at, updated_at, CURRENT_TIMESTAMP) + INTERVAL '10 minutes'
WHERE status = 'PROCESSING'
  AND lease_expires_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_financial_execution_outbox_claim_token
    ON financial.financial_execution_outbox(claim_token)
    WHERE claim_token IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_financial_execution_outbox_lease
    ON financial.financial_execution_outbox(status, lease_expires_at)
    WHERE status = 'PROCESSING';

CREATE INDEX IF NOT EXISTS idx_financial_execution_outbox_execution_reference
    ON financial.financial_execution_outbox(execution_reference)
    WHERE execution_reference IS NOT NULL;
