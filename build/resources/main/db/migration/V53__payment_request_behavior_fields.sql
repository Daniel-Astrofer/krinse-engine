ALTER TABLE financial.payment_requests
    ADD COLUMN IF NOT EXISTS behavior_contract TEXT,
    ADD COLUMN IF NOT EXISTS partial_payment_received BIGINT,
    ADD COLUMN IF NOT EXISTS webhook_url VARCHAR(2048);
