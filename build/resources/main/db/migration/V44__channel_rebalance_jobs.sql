-- Queued channel rebalance work after binary decision pass (doc §3.2).
CREATE TABLE IF NOT EXISTS financial.channel_rebalance_jobs (
    id UUID PRIMARY KEY,
    decision_id UUID,
    channel_point VARCHAR(128) NOT NULL,
    peer_pubkey VARCHAR(128),
    estimated_cost_sats BIGINT NOT NULL DEFAULT 0,
    expected_gain_sats BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    provider_reference VARCHAR(255),
    last_error VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    CONSTRAINT chk_channel_rebal_status
        CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT chk_channel_rebal_cost CHECK (estimated_cost_sats >= 0),
    CONSTRAINT chk_channel_rebal_gain CHECK (expected_gain_sats >= 0)
);

CREATE INDEX IF NOT EXISTS idx_channel_rebal_status_created
    ON financial.channel_rebalance_jobs (status, created_at ASC);

CREATE UNIQUE INDEX IF NOT EXISTS uq_channel_rebal_pending_point
    ON financial.channel_rebalance_jobs (channel_point)
    WHERE status IN ('PENDING', 'IN_PROGRESS');
