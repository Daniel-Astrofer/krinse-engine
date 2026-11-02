-- Security audit hardening: granular transaction state columns,
-- notification outbox, network observation log, idempotency claims,
-- and balance movement uniqueness constraint.
--
-- Phase 1: All new columns are nullable. Existing code continues writing
-- to financial.transactions_master.status. New columns backfilled in Phase 2.

-- ============================================================
-- 1. New columns on financial.transactions_master
-- ============================================================
ALTER TABLE financial.transactions_master
    ADD COLUMN IF NOT EXISTS network_status VARCHAR(32);

ALTER TABLE financial.transactions_master
    ADD COLUMN IF NOT EXISTS accounting_status VARCHAR(32);

ALTER TABLE financial.transactions_master
    ADD COLUMN IF NOT EXISTS business_status VARCHAR(32);

ALTER TABLE financial.transactions_master
    ADD COLUMN IF NOT EXISTS finality_status VARCHAR(32);

ALTER TABLE financial.transactions_master
    ADD COLUMN IF NOT EXISTS network_first_seen_at TIMESTAMPTZ;

ALTER TABLE financial.transactions_master
    ADD COLUMN IF NOT EXISTS network_last_seen_at TIMESTAMPTZ;

ALTER TABLE financial.transactions_master
    ADD COLUMN IF NOT EXISTS network_not_found_since TIMESTAMPTZ;

ALTER TABLE financial.transactions_master
    ADD COLUMN IF NOT EXISTS network_not_found_count INTEGER DEFAULT 0;

ALTER TABLE financial.transactions_master
    ADD COLUMN IF NOT EXISTS block_hash VARCHAR(64);

ALTER TABLE financial.transactions_master
    ADD COLUMN IF NOT EXISTS block_height INTEGER;

ALTER TABLE financial.transactions_master
    ADD COLUMN IF NOT EXISTS replaced_by_txid VARCHAR(64);

ALTER TABLE financial.transactions_master
    ADD COLUMN IF NOT EXISTS finalized_at TIMESTAMPTZ;

ALTER TABLE financial.transactions_master
    ADD COLUMN IF NOT EXISTS reconciliation_reason TEXT;

ALTER TABLE financial.transactions_master
    ADD COLUMN IF NOT EXISTS conflicted_at TIMESTAMPTZ;

ALTER TABLE financial.transactions_master
    ADD COLUMN IF NOT EXISTS replacement_txid VARCHAR(64);

ALTER TABLE financial.transactions_master
    ADD COLUMN IF NOT EXISTS prepared_raw_tx_hash VARCHAR(64);

ALTER TABLE financial.transactions_master
    ADD COLUMN IF NOT EXISTS confirmation_monitoring_active BOOLEAN DEFAULT true;

ALTER TABLE financial.transactions_master
    ADD COLUMN IF NOT EXISTS last_chain_probe_at TIMESTAMPTZ;

ALTER TABLE financial.transactions_master
    ADD COLUMN IF NOT EXISTS last_chain_probe_status VARCHAR(32);

ALTER TABLE financial.transactions_master
    ADD COLUMN IF NOT EXISTS mempool_last_seen_at TIMESTAMPTZ;

-- ============================================================
-- 2. Financial notification outbox
-- ============================================================
CREATE TABLE IF NOT EXISTS financial.kfe_financial_notification_outbox (
    id UUID PRIMARY KEY,
    event_id UUID UNIQUE NOT NULL,
    user_id BIGINT NOT NULL,
    transaction_id UUID,
    event_type VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    claimed_by VARCHAR(128),
    claimed_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delivered_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_notif_outbox_status_next
    ON financial.kfe_financial_notification_outbox (status, next_attempt_at)
    WHERE status IN ('PENDING', 'FAILED');

CREATE INDEX IF NOT EXISTS idx_notif_outbox_user_created
    ON financial.kfe_financial_notification_outbox (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_notif_outbox_tx
    ON financial.kfe_financial_notification_outbox (transaction_id)
    WHERE transaction_id IS NOT NULL;

-- ============================================================
-- 3. Network observation log (append-only audit)
-- ============================================================
CREATE TABLE IF NOT EXISTS financial.kfe_network_observation_log (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL,
    txid VARCHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,
    confirmations INTEGER NOT NULL,
    block_hash VARCHAR(64),
    block_height INTEGER,
    observed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_network_obs_tx
    ON financial.kfe_network_observation_log (transaction_id, observed_at DESC);

CREATE INDEX IF NOT EXISTS idx_network_obs_txid
    ON financial.kfe_network_observation_log (txid, observed_at DESC);

-- ============================================================
-- 4. Idempotency claims (distributed exactly-once guard)
-- ============================================================
CREATE TABLE IF NOT EXISTS financial.kfe_idempotency_claim (
    claim_key VARCHAR(256) PRIMARY KEY,
    claim_token UUID NOT NULL,
    principal_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    fingerprint_hash VARCHAR(64),
    response_json TEXT,
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    lease_expires_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_idempotency_claim_lease
    ON financial.kfe_idempotency_claim (lease_expires_at)
    WHERE status IN ('CLAIMED', 'PROCESSING');

-- ============================================================
-- 5. Balance movement idempotency constraint
-- Note: V37 already has a partial unique index on (transaction_id, movement_type)
-- for credit types. This adds a full constraint covering all movement types
-- with wallet_id as additional discriminator.
-- ============================================================
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uq_movement_idempotent'
          AND conrelid = 'financial.balance_movements'::regclass
    ) THEN
        ALTER TABLE financial.balance_movements
            ADD CONSTRAINT uq_movement_idempotent
            UNIQUE (transaction_id, movement_type, wallet_id);
    END IF;
END
$$;
