-- Dead-man capacity intents: open/close channels reactively (doc §3.2 / dead-man ops).
-- Execution is async; binary AND gates still apply before LND mutation.
CREATE TABLE IF NOT EXISTS financial.channel_capacity_jobs (
    id UUID PRIMARY KEY,
    decision_id UUID,
    intent VARCHAR(16) NOT NULL,
    peer_pubkey VARCHAR(128),
    channel_point VARCHAR(128),
    local_amount_sats BIGINT NOT NULL DEFAULT 0,
    estimated_cost_sats BIGINT NOT NULL DEFAULT 0,
    expected_gain_sats BIGINT NOT NULL DEFAULT 0,
    trigger_reason VARCHAR(255),
    status VARCHAR(32) NOT NULL,
    provider_reference VARCHAR(255),
    last_error VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    CONSTRAINT chk_channel_capacity_intent
        CHECK (intent IN ('OPEN', 'CLOSE')),
    CONSTRAINT chk_channel_capacity_status
        CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT chk_channel_capacity_amounts
        CHECK (local_amount_sats >= 0 AND estimated_cost_sats >= 0 AND expected_gain_sats >= 0)
);

CREATE INDEX IF NOT EXISTS idx_channel_capacity_status_created
    ON financial.channel_capacity_jobs (status, created_at ASC);

-- At most one open intent in flight per peer.
CREATE UNIQUE INDEX IF NOT EXISTS uq_channel_capacity_pending_open_peer
    ON financial.channel_capacity_jobs (peer_pubkey)
    WHERE intent = 'OPEN' AND status IN ('PENDING', 'IN_PROGRESS') AND peer_pubkey IS NOT NULL;

-- At most one close intent in flight per channel.
CREATE UNIQUE INDEX IF NOT EXISTS uq_channel_capacity_pending_close_point
    ON financial.channel_capacity_jobs (channel_point)
    WHERE intent = 'CLOSE' AND status IN ('PENDING', 'IN_PROGRESS') AND channel_point IS NOT NULL;
