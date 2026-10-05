ALTER TABLE financial.channel_rebalance_jobs
    ADD COLUMN IF NOT EXISTS claimed_by VARCHAR(128),
    ADD COLUMN IF NOT EXISTS claim_token UUID,
    ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS row_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE financial.channel_capacity_jobs
    ADD COLUMN IF NOT EXISTS claimed_by VARCHAR(128),
    ADD COLUMN IF NOT EXISTS claim_token UUID,
    ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS row_version BIGINT NOT NULL DEFAULT 0;

CREATE UNIQUE INDEX IF NOT EXISTS uq_channel_rebalance_claim_token
    ON financial.channel_rebalance_jobs(claim_token)
    WHERE claim_token IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_channel_capacity_claim_token
    ON financial.channel_capacity_jobs(claim_token)
    WHERE claim_token IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_channel_rebalance_lease
    ON financial.channel_rebalance_jobs(status, lease_expires_at)
    WHERE status = 'IN_PROGRESS';

CREATE INDEX IF NOT EXISTS idx_channel_capacity_lease
    ON financial.channel_capacity_jobs(status, lease_expires_at)
    WHERE status = 'IN_PROGRESS';
