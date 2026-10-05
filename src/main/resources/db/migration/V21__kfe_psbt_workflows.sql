CREATE TABLE IF NOT EXISTS financial.kfe_psbt_workflows (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES auth.users_credentials(id) ON DELETE CASCADE,
    wallet_id UUID NOT NULL REFERENCES financial.wallets_core(id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
    psbt TEXT NOT NULL,
    signed_psbt TEXT,
    raw_tx_hex TEXT,
    psbt_hash VARCHAR(64) NOT NULL,
    signed_psbt_hash VARCHAR(64),
    raw_tx_hash VARCHAR(64),
    broadcast_txid VARCHAR(128),
    amount_sats BIGINT NOT NULL,
    fee_sats BIGINT NOT NULL DEFAULT 0,
    destination_address VARCHAR(128) NOT NULL,
    inputs_json TEXT,
    failure_message VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    signed_at TIMESTAMP,
    broadcast_at TIMESTAMP,
    CONSTRAINT chk_kfe_psbt_workflows_status
        CHECK (status IN ('CREATED', 'SIGNED', 'FINALIZED', 'BROADCAST', 'FAILED')),
    CONSTRAINT chk_kfe_psbt_workflows_amounts
        CHECK (amount_sats > 0 AND fee_sats >= 0)
);

CREATE INDEX IF NOT EXISTS idx_kfe_psbt_workflows_wallet_created
    ON financial.kfe_psbt_workflows(wallet_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_kfe_psbt_workflows_status
    ON financial.kfe_psbt_workflows(status);
