CREATE TABLE IF NOT EXISTS financial.kfe_message_inbox (
    id UUID PRIMARY KEY,
    message_id UUID NOT NULL UNIQUE,
    message_kind VARCHAR(32) NOT NULL,
    message_type VARCHAR(128) NOT NULL,
    schema_version INTEGER NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    correlation_id UUID,
    causation_id UUID,
    aggregate_id VARCHAR(160),
    aggregate_version BIGINT NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(256),
    payload_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    claimed_by VARCHAR(128),
    claimed_until TIMESTAMPTZ,
    claim_token UUID,
    quarantine_reason VARCHAR(255),
    processed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_kfe_message_inbox_status CHECK (status IN ('PENDING','PROCESSING','PROCESSED','FAILED_RETRYABLE','QUARANTINED')),
    CONSTRAINT chk_kfe_message_inbox_attempts CHECK (attempts >= 0),
    CONSTRAINT chk_kfe_message_inbox_schema_version CHECK (schema_version > 0)
);

CREATE INDEX IF NOT EXISTS idx_kfe_message_inbox_due
    ON financial.kfe_message_inbox(status, next_attempt_at, created_at);

CREATE UNIQUE INDEX IF NOT EXISTS uq_kfe_message_inbox_claim_token
    ON financial.kfe_message_inbox(claim_token)
    WHERE claim_token IS NOT NULL;

ALTER TABLE financial.kfe_financial_notification_outbox
    ADD COLUMN IF NOT EXISTS claim_token UUID;

ALTER TABLE financial.kfe_financial_notification_outbox
    ADD COLUMN IF NOT EXISTS schema_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS correlation_id UUID,
    ADD COLUMN IF NOT EXISTS causation_id UUID;

ALTER TABLE financial.kfe_financial_notification_outbox
    ADD COLUMN IF NOT EXISTS row_version BIGINT NOT NULL DEFAULT 0;

CREATE UNIQUE INDEX IF NOT EXISTS uq_kfe_fin_notification_claim_token
    ON financial.kfe_financial_notification_outbox(claim_token)
    WHERE claim_token IS NOT NULL;
