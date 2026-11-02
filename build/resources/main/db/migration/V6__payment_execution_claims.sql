ALTER TABLE financial.payment_execution_outbox
    ADD COLUMN IF NOT EXISTS claimed_by VARCHAR(128);

ALTER TABLE financial.payment_execution_outbox
    ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMP;

ALTER TABLE financial.payment_execution_outbox
    DROP CONSTRAINT IF EXISTS payment_execution_outbox_status_check;

ALTER TABLE financial.payment_execution_outbox
    ADD CONSTRAINT payment_execution_outbox_status_check
    CHECK (status IN (
        'PENDING',
        'PROCESSING',
        'DISPATCHED',
        'SETTLED',
        'UNKNOWN',
        'FAILED_RETRYABLE',
        'FAILED_FINAL'
    ));

CREATE INDEX IF NOT EXISTS idx_payment_execution_claim
    ON financial.payment_execution_outbox(status, claimed_at);
