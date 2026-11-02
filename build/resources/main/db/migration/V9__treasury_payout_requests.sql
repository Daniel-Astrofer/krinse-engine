ALTER TABLE financial.siphon_requests
    ADD COLUMN IF NOT EXISTS destination_address TEXT;

ALTER TABLE financial.siphon_requests
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(160);

ALTER TABLE financial.siphon_requests
    ADD COLUMN IF NOT EXISTS requested_by VARCHAR(128);

ALTER TABLE financial.siphon_requests
    ADD COLUMN IF NOT EXISTS revenue_cutoff_at TIMESTAMP;

ALTER TABLE financial.siphon_requests
    ADD COLUMN IF NOT EXISTS approved_by VARCHAR(128);

ALTER TABLE financial.siphon_requests
    ADD COLUMN IF NOT EXISTS approved_at TIMESTAMP;

ALTER TABLE financial.siphon_requests
    ADD COLUMN IF NOT EXISTS approval_reference VARCHAR(255);

ALTER TABLE financial.siphon_requests
    ADD COLUMN IF NOT EXISTS queued_at TIMESTAMP;

ALTER TABLE financial.siphon_requests
    ADD COLUMN IF NOT EXISTS attempts INT NOT NULL DEFAULT 0;

ALTER TABLE financial.siphon_requests
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP;

ALTER TABLE financial.siphon_requests
    ADD COLUMN IF NOT EXISTS claimed_by VARCHAR(128);

ALTER TABLE financial.siphon_requests
    ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMP;

ALTER TABLE financial.siphon_requests
    ADD COLUMN IF NOT EXISTS executed_at TIMESTAMP;

ALTER TABLE financial.siphon_requests
    ADD COLUMN IF NOT EXISTS provider_reference VARCHAR(255);

ALTER TABLE financial.siphon_requests
    ADD COLUMN IF NOT EXISTS blockchain_txid VARCHAR(128);

ALTER TABLE financial.siphon_requests
    ADD COLUMN IF NOT EXISTS provider_status VARCHAR(64);

ALTER TABLE financial.siphon_requests
    ADD COLUMN IF NOT EXISTS last_error TEXT;

ALTER TABLE financial.siphon_requests
    ADD COLUMN IF NOT EXISTS retryable BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE financial.siphon_requests
    ADD COLUMN IF NOT EXISTS cancelled_by VARCHAR(128);

ALTER TABLE financial.siphon_requests
    ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMP;

ALTER TABLE financial.siphon_requests
    ADD COLUMN IF NOT EXISTS cancel_reason TEXT;

ALTER TABLE financial.siphon_requests
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;

UPDATE financial.siphon_requests
   SET status = CASE status
       WHEN 'PENDING' THEN 'REQUESTED'
       WHEN 'EXECUTED' THEN 'SETTLED'
       WHEN 'CANCELLED' THEN 'CANCELLED'
       ELSE status
   END
 WHERE status IN ('PENDING', 'EXECUTED', 'CANCELLED');

UPDATE financial.siphon_requests
   SET idempotency_key = CONCAT('legacy-siphon-', CAST(id AS VARCHAR))
 WHERE idempotency_key IS NULL OR idempotency_key = '';

UPDATE financial.siphon_requests
   SET destination_address = 'legacy-manual-settlement'
 WHERE destination_address IS NULL OR destination_address = '';

UPDATE financial.siphon_requests
   SET revenue_cutoff_at = requested_at
 WHERE revenue_cutoff_at IS NULL;

UPDATE financial.siphon_requests
   SET next_attempt_at = executable_after
 WHERE next_attempt_at IS NULL;

UPDATE financial.siphon_requests
   SET updated_at = requested_at
 WHERE updated_at IS NULL;

ALTER TABLE financial.siphon_requests
    ALTER COLUMN idempotency_key SET NOT NULL;

ALTER TABLE financial.siphon_requests
    ALTER COLUMN destination_address SET NOT NULL;

ALTER TABLE financial.siphon_requests
    ALTER COLUMN revenue_cutoff_at SET NOT NULL;

ALTER TABLE financial.siphon_requests
    ALTER COLUMN next_attempt_at SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_siphon_requests_idempotency
    ON financial.siphon_requests(idempotency_key);

CREATE INDEX IF NOT EXISTS idx_siphon_requests_status_next
    ON financial.siphon_requests(status, next_attempt_at);

CREATE INDEX IF NOT EXISTS idx_siphon_requests_claim
    ON financial.siphon_requests(status, claimed_at);
