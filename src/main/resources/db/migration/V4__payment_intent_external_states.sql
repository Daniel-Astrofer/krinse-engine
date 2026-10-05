ALTER TABLE financial.payment_intents
    DROP CONSTRAINT IF EXISTS payment_intents_status_check;

ALTER TABLE financial.payment_intents
    ADD CONSTRAINT payment_intents_status_check
    CHECK (status IN (
        'CREATED',
        'QUOTED',
        'CONFIRMED',
        'PROCESSING',
        'ACCEPTED_BY_PROVIDER',
        'REQUIRES_RECONCILIATION',
        'SETTLED',
        'FAILED',
        'CANCELED',
        'EXPIRED'
    ));
