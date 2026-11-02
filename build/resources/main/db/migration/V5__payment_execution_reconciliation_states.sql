ALTER TABLE financial.payment_execution_outbox
    DROP CONSTRAINT IF EXISTS payment_execution_outbox_status_check;

ALTER TABLE financial.payment_execution_outbox
    ADD CONSTRAINT payment_execution_outbox_status_check
    CHECK (status IN (
        'PENDING',
        'DISPATCHED',
        'SETTLED',
        'UNKNOWN',
        'FAILED_RETRYABLE',
        'FAILED_FINAL'
    ));
