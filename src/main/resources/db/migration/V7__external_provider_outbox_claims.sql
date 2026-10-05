ALTER TABLE financial.external_provider_outbox
    ADD COLUMN IF NOT EXISTS claimed_by VARCHAR(128);

ALTER TABLE financial.external_provider_outbox
    ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_external_outbox_claim
    ON financial.external_provider_outbox(status, claimed_at);
