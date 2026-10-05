CREATE TABLE IF NOT EXISTS financial.wallets_core (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES auth.users_credentials(id) ON DELETE CASCADE,
    kind VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    label VARCHAR(96) NOT NULL,
    asset VARCHAR(16) NOT NULL DEFAULT 'BTC',
    spendable BOOLEAN NOT NULL DEFAULT TRUE,
    mpc_public_key TEXT,
    xpub TEXT,
    descriptor TEXT,
    fingerprint VARCHAR(64),
    derivation_path VARCHAR(160),
    last_derived_index INTEGER NOT NULL DEFAULT -1,
    quorum_policy_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_wallets_core_kind
        CHECK (kind IN ('INTERNAL', 'CUSTODIAL_ONCHAIN', 'WATCH_ONLY')),
    CONSTRAINT chk_wallets_core_status
        CHECK (status IN ('CREATING', 'ACTIVE', 'FROZEN', 'ROTATING_ADDRESS',
                          'KEYGEN_FAILED', 'QUORUM_BLOCKED', 'ARCHIVED'))
);

CREATE INDEX IF NOT EXISTS idx_wallets_core_user_created
    ON financial.wallets_core(user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_wallets_core_kind_status
    ON financial.wallets_core(kind, status);

CREATE TABLE IF NOT EXISTS financial.wallet_addresses (
    id UUID PRIMARY KEY,
    wallet_id UUID NOT NULL REFERENCES financial.wallets_core(id) ON DELETE CASCADE,
    address VARCHAR(128) NOT NULL UNIQUE,
    address_role VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    derivation_path VARCHAR(160),
    derivation_index INTEGER,
    provider_reference VARCHAR(255),
    first_seen_txid VARCHAR(128),
    last_seen_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    retired_at TIMESTAMP,
    CONSTRAINT chk_wallet_addresses_role
        CHECK (address_role IN ('RECEIVE', 'CHANGE', 'MONITOR')),
    CONSTRAINT chk_wallet_addresses_status
        CHECK (status IN ('ACTIVE', 'RETIRED', 'OBSERVED', 'BLOCKED'))
);

CREATE INDEX IF NOT EXISTS idx_wallet_addresses_wallet_status
    ON financial.wallet_addresses(wallet_id, status, created_at DESC);

CREATE TABLE IF NOT EXISTS financial.balances_core (
    wallet_id UUID NOT NULL REFERENCES financial.wallets_core(id) ON DELETE CASCADE,
    asset VARCHAR(16) NOT NULL DEFAULT 'BTC',
    available_sats BIGINT NOT NULL DEFAULT 0,
    pending_sats BIGINT NOT NULL DEFAULT 0,
    locked_sats BIGINT NOT NULL DEFAULT 0,
    auto_hold_sats BIGINT NOT NULL DEFAULT 0,
    observed_sats BIGINT NOT NULL DEFAULT 0,
    nonce BIGINT NOT NULL DEFAULT 0,
    last_hash VARCHAR(64) NOT NULL,
    balance_signature VARCHAR(256) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (wallet_id, asset),
    CONSTRAINT chk_balances_core_non_negative
        CHECK (available_sats >= 0 AND pending_sats >= 0 AND locked_sats >= 0
               AND auto_hold_sats >= 0 AND observed_sats >= 0)
);

CREATE TABLE IF NOT EXISTS financial.transactions_master (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(180) NOT NULL,
    user_id BIGINT NOT NULL REFERENCES auth.users_credentials(id) ON DELETE CASCADE,
    source_wallet_id UUID REFERENCES financial.wallets_core(id),
    destination_wallet_id UUID REFERENCES financial.wallets_core(id),
    rail VARCHAR(32) NOT NULL,
    direction VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    gross_amount_sats BIGINT NOT NULL DEFAULT 0,
    receiver_amount_sats BIGINT NOT NULL DEFAULT 0,
    network_fee_sats BIGINT NOT NULL DEFAULT 0,
    kerosene_fee_sats BIGINT NOT NULL DEFAULT 0,
    total_debit_sats BIGINT NOT NULL DEFAULT 0,
    quorum_proposal_hash VARCHAR(64),
    quorum_ack_count INTEGER NOT NULL DEFAULT 0,
    provider VARCHAR(64),
    provider_reference VARCHAR(255),
    blockchain_txid VARCHAR(128),
    payment_hash VARCHAR(128),
    confirmations INTEGER NOT NULL DEFAULT 0,
    failure_code VARCHAR(64),
    failure_message VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_user_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT chk_transactions_master_rail
        CHECK (rail IN ('INTERNAL', 'ONCHAIN', 'LIGHTNING')),
    CONSTRAINT chk_transactions_master_direction
        CHECK (direction IN ('INBOUND', 'OUTBOUND', 'INTERNAL')),
    CONSTRAINT chk_transactions_master_status
        CHECK (status IN ('INTENT', 'VALIDATING', 'QUORUM_SYNC', 'LOCKED',
                          'EXECUTING', 'SETTLED', 'FAILED', 'REQUIRES_RECONCILIATION')),
    CONSTRAINT chk_transactions_master_amounts_non_negative
        CHECK (gross_amount_sats >= 0 AND receiver_amount_sats >= 0
               AND network_fee_sats >= 0 AND kerosene_fee_sats >= 0
               AND total_debit_sats >= 0)
);

CREATE INDEX IF NOT EXISTS idx_transactions_master_user_created
    ON financial.transactions_master(user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_transactions_master_status
    ON financial.transactions_master(status);

CREATE INDEX IF NOT EXISTS idx_transactions_master_txid
    ON financial.transactions_master(blockchain_txid)
    WHERE blockchain_txid IS NOT NULL;

CREATE TABLE IF NOT EXISTS financial.transaction_idempotency (
    user_id BIGINT NOT NULL REFERENCES auth.users_credentials(id) ON DELETE CASCADE,
    idempotency_key VARCHAR(180) NOT NULL,
    transaction_id UUID REFERENCES financial.transactions_master(id) ON DELETE SET NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP,
    PRIMARY KEY (user_id, idempotency_key)
);

CREATE TABLE IF NOT EXISTS financial.balance_movements (
    id UUID PRIMARY KEY,
    transaction_id UUID REFERENCES financial.transactions_master(id) ON DELETE SET NULL,
    wallet_id UUID NOT NULL REFERENCES financial.wallets_core(id) ON DELETE CASCADE,
    movement_type VARCHAR(32) NOT NULL,
    amount_sats BIGINT NOT NULL,
    from_bucket VARCHAR(32),
    to_bucket VARCHAR(32),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_balance_movements_transaction
    ON financial.balance_movements(transaction_id, created_at);

CREATE INDEX IF NOT EXISTS idx_balance_movements_wallet
    ON financial.balance_movements(wallet_id, created_at DESC);

CREATE TABLE IF NOT EXISTS financial.financial_audit_log (
    sequence_number BIGSERIAL PRIMARY KEY,
    id UUID NOT NULL UNIQUE,
    transaction_id UUID REFERENCES financial.transactions_master(id) ON DELETE SET NULL,
    wallet_id UUID REFERENCES financial.wallets_core(id) ON DELETE SET NULL,
    event_type VARCHAR(96) NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32),
    payload_hash VARCHAR(64) NOT NULL,
    previous_hash VARCHAR(64) NOT NULL,
    event_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_financial_audit_log_transaction
    ON financial.financial_audit_log(transaction_id, sequence_number);

CREATE INDEX IF NOT EXISTS idx_financial_audit_log_wallet
    ON financial.financial_audit_log(wallet_id, sequence_number);

CREATE TABLE IF NOT EXISTS financial.user_statement_24h (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES auth.users_credentials(id) ON DELETE CASCADE,
    transaction_id UUID NOT NULL REFERENCES financial.transactions_master(id) ON DELETE CASCADE,
    wallet_id UUID REFERENCES financial.wallets_core(id) ON DELETE SET NULL,
    display_payload_json TEXT NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_user_statement_24h_user_created
    ON financial.user_statement_24h(user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_user_statement_24h_expiry
    ON financial.user_statement_24h(expires_at);

CREATE TABLE IF NOT EXISTS financial.financial_execution_outbox (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL REFERENCES financial.transactions_master(id) ON DELETE CASCADE,
    operation VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    payload_json TEXT,
    payload_hash VARCHAR(64) NOT NULL,
    provider_reference VARCHAR(255),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP,
    claimed_by VARCHAR(128),
    claimed_at TIMESTAMP,
    dispatched_at TIMESTAMP,
    last_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_financial_execution_outbox_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'DISPATCHED',
                          'FAILED_RETRYABLE', 'FAILED_FINAL', 'UNKNOWN'))
);

CREATE INDEX IF NOT EXISTS idx_financial_execution_outbox_status
    ON financial.financial_execution_outbox(status, next_attempt_at);

CREATE OR REPLACE VIEW financial.wallet_dashboard_view AS
SELECT
    w.id AS wallet_id,
    w.user_id,
    w.kind,
    w.status,
    w.label,
    w.asset,
    w.spendable,
    COALESCE(b.available_sats, 0) AS available_sats,
    COALESCE(b.pending_sats, 0) AS pending_sats,
    COALESCE(b.locked_sats, 0) AS locked_sats,
    COALESCE(b.auto_hold_sats, 0) AS auto_hold_sats,
    COALESCE(b.observed_sats, 0) AS observed_sats,
    (
        SELECT a.address
        FROM financial.wallet_addresses a
        WHERE a.wallet_id = w.id AND a.status = 'ACTIVE'
        ORDER BY a.created_at DESC
        LIMIT 1
    ) AS active_address,
    w.created_at,
    w.updated_at
FROM financial.wallets_core w
LEFT JOIN financial.balances_core b
    ON b.wallet_id = w.id AND b.asset = w.asset;

CREATE OR REPLACE FUNCTION financial.prevent_audit_log_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'financial_audit_log is append-only. Updates and deletes are prohibited.';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS enforce_append_only_audit_log ON financial.financial_audit_log;

CREATE TRIGGER enforce_append_only_audit_log
BEFORE UPDATE OR DELETE ON financial.financial_audit_log
FOR EACH ROW
EXECUTE FUNCTION financial.prevent_audit_log_modification();
