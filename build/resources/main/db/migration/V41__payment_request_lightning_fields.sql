-- Lightning payment requests: bolt11 + payment_hash (address column is too short for invoices).
ALTER TABLE financial.payment_requests
    ADD COLUMN IF NOT EXISTS payment_request TEXT,
    ADD COLUMN IF NOT EXISTS payment_hash VARCHAR(128),
    ADD COLUMN IF NOT EXISTS provider_reference VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_payment_requests_payment_hash
    ON financial.payment_requests (payment_hash)
    WHERE payment_hash IS NOT NULL;
