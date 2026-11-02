-- Flyway V2 operational hardening for production schema.
-- Source: src/main/resources/db/migration.sql, kept idempotent while the
-- project transitions away from out-of-band SQL scripts.

-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 001: Adicionar coluna totp_secret na tabela financial.wallets
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS financial.wallets (
    id BIGSERIAL PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    user_id BIGINT NOT NULL REFERENCES auth.users_credentials(id) ON DELETE CASCADE,
    address VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    totp_secret VARCHAR(255) NOT NULL DEFAULT '',
    deposit_address VARCHAR(100),
    lightning_address VARCHAR(255),
    external_wallet_reference VARCHAR(255),
    card_number_suffix VARCHAR(4),
    card_issued_at TIMESTAMP,
    card_expires_at TIMESTAMP,
    card_last_rotated_at TIMESTAMP,
    card_sequence INTEGER NOT NULL DEFAULT 1,
    previous_card_number_suffix VARCHAR(4),
    previous_card_expires_at TIMESTAMP,
    xpub TEXT,
    wallet_mode VARCHAR(32) NOT NULL DEFAULT 'KEROSENE',
    last_derived_index INTEGER NOT NULL DEFAULT -1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_active BOOLEAN DEFAULT TRUE,
    UNIQUE(user_id, name)
);

ALTER TABLE financial.wallets
    ADD COLUMN IF NOT EXISTS totp_secret VARCHAR(255) NOT NULL DEFAULT '';

-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 002: Critical Performance Indexes (Issue 2.5)
-- All indexes use IF NOT EXISTS — safe to re-run on restart.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS financial.ledger (
    id SERIAL PRIMARY KEY,
    wallet_id BIGINT NOT NULL REFERENCES financial.wallets(id) ON DELETE CASCADE,
    balance TEXT NOT NULL,
    balance_signature VARCHAR(256),
    nonce INTEGER NOT NULL DEFAULT 0,
    last_hash VARCHAR(256) NOT NULL,
    context VARCHAR(256) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS financial.ledger_transaction_history (
    id UUID PRIMARY KEY,
    sender_identifier VARCHAR(255) NOT NULL,
    sender_user_id BIGINT,
    receiver_identifier VARCHAR(255) NOT NULL,
    receiver_user_id BIGINT,
    transaction_type VARCHAR(50) NOT NULL,
    amount NUMERIC(18, 8) NOT NULL,
    status VARCHAR(50) NOT NULL,
    network_fee NUMERIC(18, 8),
    blockchain_txid VARCHAR(255),
    context TEXT,
    created_at TIMESTAMP NOT NULL,
    confirmations INTEGER
);

-- Ledger table
CREATE INDEX IF NOT EXISTS idx_ledger_wallet_id   ON financial.ledger(wallet_id);
CREATE INDEX IF NOT EXISTS idx_ledger_nonce        ON financial.ledger(nonce);
CREATE INDEX IF NOT EXISTS idx_ledger_last_hash    ON financial.ledger(last_hash);

-- Wallet table
CREATE INDEX IF NOT EXISTS idx_wallet_user_id      ON financial.wallets(user_id);

-- Ledger transaction history
-- LEGACY/EPHEMERAL: financial.ledger_transaction_history is a readable
-- operational buffer retained for short mobile synchronization and settlement
-- convergence. It is not a durable user statement; default retention is 24h.
CREATE INDEX IF NOT EXISTS idx_ledger_history_receiver ON financial.ledger_transaction_history(receiver_user_id);
CREATE INDEX IF NOT EXISTS idx_ledger_history_sender   ON financial.ledger_transaction_history(sender_identifier);
CREATE INDEX IF NOT EXISTS idx_ledger_history_created  ON financial.ledger_transaction_history(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ledger_history_txid     ON financial.ledger_transaction_history(blockchain_txid)
    WHERE blockchain_txid IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ledger_history_status   ON financial.ledger_transaction_history(status);

-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 003: Adicionar coluna deposit_address em financial.wallets
-- Armazena o endereço Bitcoin público (P2WPKH/Bech32 bc1q...) do usuário.
-- É usado para identificar a carteira de destino em depósitos on-chain.
-- DISTINTO do campo `address` que armazena o hash criptografado da passphrase.
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE financial.wallets
    ADD COLUMN IF NOT EXISTS deposit_address VARCHAR(100) NULL;

-- Índice único para garantir que nenhum endereço Bitcoin seja compartilhado entre carteiras
CREATE UNIQUE INDEX IF NOT EXISTS idx_wallet_deposit_address
    ON financial.wallets(deposit_address)
    WHERE deposit_address IS NOT NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 004: Passkey Schema Harmonization
-- Hibernate 'update' often creates a mess when renaming columns with constraints.
-- We drop the legacy 'public_key' to resolve NOT NULL violations.
-- ─────────────────────────────────────────────────────────────────────────────

-- Remove legacy column directly (PostgreSQL 9.0+ supports IF EXISTS)
ALTER TABLE auth.passkey_credentials DROP COLUMN IF EXISTS public_key;

-- Ensure device_name exists (idempotent)
ALTER TABLE auth.passkey_credentials ADD COLUMN IF NOT EXISTS device_name VARCHAR(255);
ALTER TABLE auth.passkey_credentials ADD COLUMN IF NOT EXISTS relying_party_id VARCHAR(255);
ALTER TABLE auth.passkey_credentials ADD COLUMN IF NOT EXISTS origin_host VARCHAR(255);

-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 005: Dev balance claim guard
-- Prevents the development balance injector from crediting a test balance more
-- than once for the same user.
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE auth.users_credentials
    ADD COLUMN IF NOT EXISTS test_balance_claimed BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE auth.users_credentials
    ADD COLUMN IF NOT EXISTS shamir_total_shares INTEGER;

ALTER TABLE auth.users_credentials
    ADD COLUMN IF NOT EXISTS shamir_threshold INTEGER;

ALTER TABLE auth.users_credentials
    ADD COLUMN IF NOT EXISTS multisig_threshold INTEGER NOT NULL DEFAULT 2;

-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 006: XPUB derivation state for deterministic wallet addresses
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE financial.wallets
    ADD COLUMN IF NOT EXISTS xpub TEXT;

ALTER TABLE financial.wallets
    ADD COLUMN IF NOT EXISTS wallet_mode VARCHAR(32) NOT NULL DEFAULT 'KEROSENE';

ALTER TABLE financial.wallets
    ADD COLUMN IF NOT EXISTS last_derived_index INTEGER NOT NULL DEFAULT -1;

UPDATE financial.wallets
    SET wallet_mode = 'KEROSENE'
    WHERE wallet_mode IS NULL;

UPDATE financial.wallets
    SET last_derived_index = -1
    WHERE last_derived_index IS NULL;

ALTER TABLE financial.wallets
    ALTER COLUMN last_derived_index SET DEFAULT -1;

ALTER TABLE financial.wallets
    ALTER COLUMN wallet_mode SET DEFAULT 'KEROSENE';

ALTER TABLE financial.wallets
    ALTER COLUMN wallet_mode SET NOT NULL;

ALTER TABLE financial.wallets
    ALTER COLUMN last_derived_index SET NOT NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 007: External network wallet metadata
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE financial.wallets
    ADD COLUMN IF NOT EXISTS lightning_address VARCHAR(255);

ALTER TABLE financial.wallets
    ADD COLUMN IF NOT EXISTS external_wallet_reference VARCHAR(255);

ALTER TABLE financial.wallets
    ADD COLUMN IF NOT EXISTS card_number_suffix VARCHAR(4);

ALTER TABLE financial.wallets
    ADD COLUMN IF NOT EXISTS card_issued_at TIMESTAMP;

ALTER TABLE financial.wallets
    ADD COLUMN IF NOT EXISTS card_expires_at TIMESTAMP;

ALTER TABLE financial.wallets
    ADD COLUMN IF NOT EXISTS card_last_rotated_at TIMESTAMP;

ALTER TABLE financial.wallets
    ADD COLUMN IF NOT EXISTS card_sequence INTEGER NOT NULL DEFAULT 1;

ALTER TABLE financial.wallets
    ADD COLUMN IF NOT EXISTS previous_card_number_suffix VARCHAR(4);

ALTER TABLE financial.wallets
    ADD COLUMN IF NOT EXISTS previous_card_expires_at TIMESTAMP;

CREATE UNIQUE INDEX IF NOT EXISTS idx_wallet_lightning_address
    ON financial.wallets(lightning_address)
    WHERE lightning_address IS NOT NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 008: External network transfers and custody events
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS financial.network_transfers (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES auth.users_credentials(id) ON DELETE CASCADE,
    wallet_id BIGINT NOT NULL REFERENCES financial.wallets(id) ON DELETE CASCADE,
    wallet_name_snapshot VARCHAR(255) NOT NULL,
    network VARCHAR(32) NOT NULL,
    transfer_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    destination TEXT,
    external_reference VARCHAR(255),
    invoice_data TEXT,
    amount_btc NUMERIC(19, 8),
    network_fee_btc NUMERIC(19, 8),
    platform_fee_btc NUMERIC(19, 8),
    total_debited_btc NUMERIC(19, 8),
    context TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_network_transfers_user_created
    ON financial.network_transfers(user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_network_transfers_wallet
    ON financial.network_transfers(wallet_id);

CREATE INDEX IF NOT EXISTS idx_network_transfers_status
    ON financial.network_transfers(status);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
        WHERE c.contype = 'f'
          AND c.conrelid = 'financial.network_transfers'::regclass
          AND c.confrelid = 'auth.users_credentials'::regclass
          AND a.attname = 'user_id'
    ) THEN
        ALTER TABLE financial.network_transfers
            ADD CONSTRAINT fk_network_transfers_user
            FOREIGN KEY (user_id)
            REFERENCES auth.users_credentials(id)
            ON DELETE CASCADE
            NOT VALID;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
        WHERE c.contype = 'f'
          AND c.conrelid = 'financial.network_transfers'::regclass
          AND c.confrelid = 'financial.wallets'::regclass
          AND a.attname = 'wallet_id'
    ) THEN
        ALTER TABLE financial.network_transfers
            ADD CONSTRAINT fk_network_transfers_wallet
            FOREIGN KEY (wallet_id)
            REFERENCES financial.wallets(id)
            ON DELETE CASCADE
            NOT VALID;
    END IF;
END $$;

-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 026: Payment intent quote/confirm flow
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS financial.receiving_methods (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES auth.users_credentials(id) ON DELETE CASCADE,
    type VARCHAR(32) NOT NULL CHECK (type IN ('INTERNAL','LIGHTNING','ONCHAIN')),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE','REVOKED','PENDING_VERIFICATION')),
    label VARCHAR(128),
    priority INTEGER NOT NULL DEFAULT 100,
    metadata_encrypted TEXT,
    public_reference_hash VARCHAR(128),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_receiving_methods_user_type_status
    ON financial.receiving_methods(user_id, type, status);
CREATE INDEX IF NOT EXISTS idx_receiving_methods_public_ref
    ON financial.receiving_methods(public_reference_hash);

CREATE TABLE IF NOT EXISTS financial.payment_intents (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(128),
    sender_user_id BIGINT NOT NULL REFERENCES auth.users_credentials(id) ON DELETE CASCADE,
    locked_wallet_id BIGINT,
    receiver_user_id BIGINT REFERENCES auth.users_credentials(id) ON DELETE SET NULL,
    receiver_display_name VARCHAR(128),
    receiver_identifier VARCHAR(255),
    external_destination TEXT,
    rail VARCHAR(32) NOT NULL CHECK (rail IN ('INTERNAL','LIGHTNING','ONCHAIN')),
    fee_mode VARCHAR(32) NOT NULL CHECK (fee_mode IN ('SENDER_PAYS','RECIPIENT_PAYS')),
    requested_amount_fiat NUMERIC(19, 2) NOT NULL,
    fiat_currency VARCHAR(8) NOT NULL DEFAULT 'BRL',
    asset VARCHAR(16) NOT NULL DEFAULT 'BTC',
    requested_amount_sats BIGINT NOT NULL,
    receiver_amount_sats BIGINT NOT NULL,
    total_debit_sats BIGINT NOT NULL,
    network_fee_sats BIGINT NOT NULL DEFAULT 0,
    kerosene_fee_sats BIGINT NOT NULL DEFAULT 0,
    fx_rate NUMERIC(19, 2) NOT NULL,
    quote_expires_at TIMESTAMP NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('CREATED','QUOTED','CONFIRMED','PROCESSING','SETTLED','FAILED','CANCELED','EXPIRED')),
    failure_code VARCHAR(64),
    failure_message VARCHAR(255),
    speed VARCHAR(32) CHECK (speed IS NULL OR speed IN ('ECONOMY','NORMAL','FAST')),
    warnings TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_payment_intents_idempotency_unique
    ON financial.payment_intents(idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_payment_intents_sender_created
    ON financial.payment_intents(sender_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_payment_intents_status
    ON financial.payment_intents(status);

CREATE TABLE IF NOT EXISTS financial.payment_audit_events (
    id UUID PRIMARY KEY,
    actor_user_id BIGINT,
    payment_intent_id UUID REFERENCES financial.payment_intents(id) ON DELETE SET NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_hash VARCHAR(128) NOT NULL,
    previous_hash VARCHAR(128),
    current_hash VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_payment_audit_intent_created
    ON financial.payment_audit_events(payment_intent_id, created_at);
CREATE INDEX IF NOT EXISTS idx_payment_audit_current_hash
    ON financial.payment_audit_events(current_hash);

ALTER TABLE financial.payment_intents
    ADD COLUMN IF NOT EXISTS locked_wallet_id BIGINT;

CREATE TABLE IF NOT EXISTS financial.payment_execution_outbox (
    id UUID PRIMARY KEY,
    payment_intent_id UUID NOT NULL REFERENCES financial.payment_intents(id) ON DELETE CASCADE,
    rail VARCHAR(32) NOT NULL CHECK (rail IN ('LIGHTNING','ONCHAIN')),
    idempotency_key VARCHAR(160) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','DISPATCHED','FAILED_RETRYABLE','FAILED_FINAL')),
    attempts INTEGER NOT NULL DEFAULT 0,
    payload_json TEXT,
    provider_reference VARCHAR(255),
    last_error TEXT,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    dispatched_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_payment_execution_intent UNIQUE(payment_intent_id),
    CONSTRAINT uk_payment_execution_idempotency UNIQUE(idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_payment_execution_status_next
    ON financial.payment_execution_outbox(status, next_attempt_at);

-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 024: Admin access key flow and auditable authenticated devices
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE auth.passkey_credentials ADD COLUMN IF NOT EXISTS brand VARCHAR(255);
ALTER TABLE auth.passkey_credentials ADD COLUMN IF NOT EXISTS model VARCHAR(255);
ALTER TABLE auth.passkey_credentials ADD COLUMN IF NOT EXISTS serial_number VARCHAR(255);
ALTER TABLE auth.passkey_credentials ADD COLUMN IF NOT EXISTS device_install_id VARCHAR(128);
ALTER TABLE auth.passkey_credentials ADD COLUMN IF NOT EXISTS platform VARCHAR(128);
ALTER TABLE auth.passkey_credentials ADD COLUMN IF NOT EXISTS browser VARCHAR(128);
ALTER TABLE auth.passkey_credentials ADD COLUMN IF NOT EXISTS status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE auth.passkey_credentials ADD COLUMN IF NOT EXISTS first_access_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE auth.passkey_credentials ADD COLUMN IF NOT EXISTS last_access_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

CREATE TABLE IF NOT EXISTS auth.admin_keys (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES auth.users_credentials(id) ON DELETE CASCADE,
    key_material_hash VARCHAR(128) NOT NULL,
    key_fingerprint VARCHAR(64) NOT NULL,
    device_install_id VARCHAR(128),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    rotated_at TIMESTAMP,
    revoked_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS auth.admin_access_devices (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES auth.users_credentials(id) ON DELETE CASCADE,
    device_id VARCHAR(128) NOT NULL,
    device_name VARCHAR(255),
    browser VARCHAR(128),
    user_agent VARCHAR(512),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    first_access_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_access_at TIMESTAMP,
    CONSTRAINT uk_admin_access_device_user_device UNIQUE(user_id, device_id)
);

CREATE TABLE IF NOT EXISTS auth.admin_access_attempts (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES auth.users_credentials(id) ON DELETE CASCADE,
    device_row_id UUID NOT NULL REFERENCES auth.admin_access_devices(id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    browser VARCHAR(128),
    user_agent VARCHAR(512),
    ip_fingerprint VARCHAR(64),
    requested_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    decided_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS auth.admin_access_events (
    id UUID PRIMARY KEY,
    admin_id BIGINT REFERENCES auth.users_credentials(id) ON DELETE SET NULL,
    device_id VARCHAR(128),
    browser VARCHAR(128),
    sanitized_user_agent VARCHAR(512),
    ip_fingerprint VARCHAR(64),
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(32) NOT NULL,
    reason VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_admin_keys_user_status
    ON auth.admin_keys(user_id, status);
CREATE INDEX IF NOT EXISTS idx_admin_access_attempts_user_status
    ON auth.admin_access_attempts(user_id, status, expires_at);
CREATE INDEX IF NOT EXISTS idx_admin_access_events_admin_time
    ON auth.admin_access_events(admin_id, occurred_at DESC);

-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 010: Inbound network transfer lifecycle metadata
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE financial.network_transfers
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_network_transfers_type_status
    ON financial.network_transfers(transfer_type, status);

-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 009: Mining marketplace inspired by hashpower rentals
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS financial.mining_rig_offers (
    id BIGSERIAL PRIMARY KEY,
    rig_code VARCHAR(64) UNIQUE NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    algorithm VARCHAR(64) NOT NULL,
    hash_unit VARCHAR(16) NOT NULL,
    price_per_unit_day_btc NUMERIC(19, 8) NOT NULL,
    projected_btc_yield_per_unit_day NUMERIC(19, 8) NOT NULL,
    projected_yield_multiplier NUMERIC(10, 8) NOT NULL DEFAULT 1.00000000,
    available_hashrate NUMERIC(19, 8) NOT NULL,
    min_rental_hours INTEGER NOT NULL DEFAULT 1,
    max_rental_hours INTEGER NOT NULL DEFAULT 168,
    provider VARCHAR(64) NOT NULL DEFAULT 'KEROSENE_INTERNAL',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_mining_rig_offers_active
    ON financial.mining_rig_offers(active);

CREATE INDEX IF NOT EXISTS idx_mining_rig_offers_algorithm
    ON financial.mining_rig_offers(algorithm);

-- Default mining catalog seed. Keep this outside application services so runtime
-- reads do not mutate catalog state.
INSERT INTO financial.mining_rig_offers (
    rig_code,
    display_name,
    algorithm,
    hash_unit,
    price_per_unit_day_btc,
    projected_btc_yield_per_unit_day,
    projected_yield_multiplier,
    available_hashrate,
    min_rental_hours,
    max_rental_hours,
    provider,
    active,
    created_at,
    updated_at
)
SELECT
    'sha256-hydro-240',
    'Hydro SHA256 240TH',
    'SHA256',
    'TH',
    0.00000850,
    0.00000720,
    0.98500000,
    1200.00000000,
    1,
    168,
    'KEROSENE_INTERNAL',
    TRUE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM financial.mining_rig_offers WHERE rig_code = 'sha256-hydro-240'
);

INSERT INTO financial.mining_rig_offers (
    rig_code,
    display_name,
    algorithm,
    hash_unit,
    price_per_unit_day_btc,
    projected_btc_yield_per_unit_day,
    projected_yield_multiplier,
    available_hashrate,
    min_rental_hours,
    max_rental_hours,
    provider,
    active,
    created_at,
    updated_at
)
SELECT
    'sha256-pro-150',
    'Pro SHA256 150TH',
    'SHA256',
    'TH',
    0.00000720,
    0.00000610,
    0.98250000,
    900.00000000,
    1,
    168,
    'KEROSENE_INTERNAL',
    TRUE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM financial.mining_rig_offers WHERE rig_code = 'sha256-pro-150'
);

INSERT INTO financial.mining_rig_offers (
    rig_code,
    display_name,
    algorithm,
    hash_unit,
    price_per_unit_day_btc,
    projected_btc_yield_per_unit_day,
    projected_yield_multiplier,
    available_hashrate,
    min_rental_hours,
    max_rental_hours,
    provider,
    active,
    created_at,
    updated_at
)
SELECT
    'scrypt-rack-18g',
    'Scrypt Rack 18GH',
    'SCRYPT',
    'GH',
    0.00012000,
    0.00010100,
    0.98000000,
    180.00000000,
    1,
    72,
    'KEROSENE_INTERNAL',
    TRUE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM financial.mining_rig_offers WHERE rig_code = 'scrypt-rack-18g'
);

CREATE TABLE IF NOT EXISTS financial.mining_allocations (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES auth.users_credentials(id) ON DELETE CASCADE,
    wallet_id BIGINT NOT NULL REFERENCES financial.wallets(id) ON DELETE CASCADE,
    rig_id BIGINT NOT NULL REFERENCES financial.mining_rig_offers(id) ON DELETE RESTRICT,
    wallet_name_snapshot VARCHAR(255) NOT NULL,
    rig_name_snapshot VARCHAR(255) NOT NULL,
    algorithm VARCHAR(64) NOT NULL,
    hash_unit VARCHAR(16) NOT NULL,
    allocated_hashrate NUMERIC(19, 8) NOT NULL,
    duration_hours INTEGER NOT NULL,
    rental_cost_btc NUMERIC(19, 8) NOT NULL,
    projected_gross_yield_btc NUMERIC(19, 8) NOT NULL,
    projected_net_yield_btc NUMERIC(19, 8) NOT NULL,
    payout_address TEXT,
    pool_url TEXT,
    worker_name VARCHAR(255),
    provider_rental_reference VARCHAR(255),
    status VARCHAR(32) NOT NULL,
    refunded_amount_btc NUMERIC(19, 8),
    settled_at TIMESTAMP,
    starts_at TIMESTAMP NOT NULL,
    ends_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_mining_allocations_user_created
    ON financial.mining_allocations(user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_mining_allocations_status
    ON financial.mining_allocations(status);

CREATE INDEX IF NOT EXISTS idx_mining_allocations_wallet
    ON financial.mining_allocations(wallet_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 010: Treasury configuration for real audit xpub scanning
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS financial.treasury_config (
    id BIGSERIAL PRIMARY KEY,
    max_withdraw_limit NUMERIC(19, 8) NOT NULL DEFAULT 1.00000000,
    audit_xpub TEXT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 011: Persistent idempotency for on-chain deposits
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS financial.processed_transactions (
    id UUID PRIMARY KEY,
    txid VARCHAR(128) NOT NULL UNIQUE,
    source VARCHAR(64) NOT NULL,
    processed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_processed_transactions_txid
    ON financial.processed_transactions(txid);

CREATE TABLE IF NOT EXISTS financial.custodial_derivation_cursors (
    cursor_key VARCHAR(64) PRIMARY KEY,
    last_issued_index INTEGER NOT NULL DEFAULT -1,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 012: Auth account password hash + activation audit timestamp
-- Separates account authentication from internal wallet recovery material.
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE auth.users_credentials
    ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255);

UPDATE auth.users_credentials
    SET password_hash = passphrase
    WHERE password_hash IS NULL
      AND passphrase IS NOT NULL;

ALTER TABLE auth.users_credentials
    ADD COLUMN IF NOT EXISTS activated_at TIMESTAMP;

UPDATE auth.users_credentials
    SET activated_at = CURRENT_TIMESTAMP
    WHERE is_active = TRUE
      AND activated_at IS NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 013: Auth support tables required by the current JPA model
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS auth.users_device (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES auth.users_credentials(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_users_device_user_id
    ON auth.users_device(user_id);

CREATE TABLE IF NOT EXISTS public.user_backup_codes (
    user_id BIGINT NOT NULL REFERENCES auth.users_credentials(id) ON DELETE CASCADE,
    code_hash VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_user_backup_codes_user_id
    ON public.user_backup_codes(user_id);

CREATE TABLE IF NOT EXISTS auth.user_app_pin_settings (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES auth.users_credentials(id) ON DELETE CASCADE,
    device_hash VARCHAR(128) NOT NULL,
    pin_hash VARCHAR(255),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMP,
    last_verified_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, device_hash)
);

CREATE INDEX IF NOT EXISTS idx_user_app_pin_user_id
    ON auth.user_app_pin_settings(user_id);

CREATE INDEX IF NOT EXISTS idx_user_app_pin_device_hash
    ON auth.user_app_pin_settings(device_hash);

-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 014: Deposit history table for inbound on-chain tracking
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.deposits (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    txid VARCHAR(255) NOT NULL UNIQUE,
    from_address VARCHAR(255) NOT NULL,
    to_address VARCHAR(255) NOT NULL,
    amount_btc NUMERIC(19, 8) NOT NULL,
    network_fee NUMERIC(19, 8),
    confirmations BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(50) NOT NULL DEFAULT 'pending',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_deposits_user_created
    ON public.deposits(user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_deposits_status
    ON public.deposits(status);

-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 015: Audit and treasury support tables
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS financial.merkle_audit (
    id UUID PRIMARY KEY,
    merkle_root VARCHAR(64) NOT NULL,
    ledger_count BIGINT NOT NULL,
    anchor_txid VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_merkle_audit_created_at
    ON financial.merkle_audit(created_at DESC);

CREATE TABLE IF NOT EXISTS financial.siphon_requests (
    id UUID PRIMARY KEY,
    amount NUMERIC(19, 8) NOT NULL,
    requested_at TIMESTAMP NOT NULL,
    executable_after TIMESTAMP NOT NULL,
    status VARCHAR(32) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_siphon_requests_status_eta
    ON financial.siphon_requests(status, executable_after);

CREATE TABLE IF NOT EXISTS financial.platform_revenue (
    id BIGSERIAL PRIMARY KEY,
    accumulated_profit NUMERIC(19, 8) NOT NULL DEFAULT 0,
    hmac_sha256 VARCHAR(255) NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 016: Wallet network metadata expected by WalletEntity
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE financial.wallets
    ADD COLUMN IF NOT EXISTS lightning_address VARCHAR(255);

ALTER TABLE financial.wallets
    ADD COLUMN IF NOT EXISTS external_wallet_reference VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS idx_wallet_lightning_address
    ON financial.wallets(lightning_address)
    WHERE lightning_address IS NOT NULL;


-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 017: Notifications table
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.notifications (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES auth.users_credentials(id) ON DELETE CASCADE,
    kind VARCHAR(64),
    severity VARCHAR(64),
    title VARCHAR(255),
    body TEXT,
    deeplink TEXT,
    entity_type VARCHAR(64),
    entity_id VARCHAR(64),
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_notifications_user_created
    ON public.notifications(user_id, created_at DESC);

-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 018: Real network transfer reconciliation metadata
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE financial.network_transfers
    ADD COLUMN IF NOT EXISTS invoice_id VARCHAR(128);

ALTER TABLE financial.network_transfers
    ADD COLUMN IF NOT EXISTS blockchain_txid VARCHAR(128);

ALTER TABLE financial.network_transfers
    ADD COLUMN IF NOT EXISTS payment_hash VARCHAR(128);

ALTER TABLE financial.network_transfers
    ADD COLUMN IF NOT EXISTS detected_at TIMESTAMP;

ALTER TABLE financial.network_transfers
    ADD COLUMN IF NOT EXISTS settled_at TIMESTAMP;

ALTER TABLE financial.network_transfers
    ADD COLUMN IF NOT EXISTS confirmations INTEGER NOT NULL DEFAULT 0;

ALTER TABLE financial.network_transfers
    ADD COLUMN IF NOT EXISTS provider_payload TEXT;

ALTER TABLE financial.network_transfers
    ADD COLUMN IF NOT EXISTS expected_amount_btc NUMERIC(19, 8);

UPDATE financial.network_transfers
    SET confirmations = 0
    WHERE confirmations IS NULL;

CREATE INDEX IF NOT EXISTS idx_network_transfers_txid
    ON financial.network_transfers(blockchain_txid);

CREATE INDEX IF NOT EXISTS idx_network_transfers_invoice_id
    ON financial.network_transfers(invoice_id);

CREATE INDEX IF NOT EXISTS idx_network_transfers_payment_hash
    ON financial.network_transfers(payment_hash);

-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 019: Persistent blockchain address watches for ZMQ reconciliation
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS financial.blockchain_address_watch (
    id UUID PRIMARY KEY,
    transfer_id UUID NOT NULL UNIQUE REFERENCES financial.network_transfers(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES auth.users_credentials(id) ON DELETE CASCADE,
    wallet_id BIGINT NOT NULL REFERENCES financial.wallets(id) ON DELETE CASCADE,
    address VARCHAR(128) NOT NULL,
    label VARCHAR(255),
    status VARCHAR(32) NOT NULL DEFAULT 'WATCHING',
    observed_txid VARCHAR(128),
    observed_amount_sats BIGINT,
    confirmations INTEGER NOT NULL DEFAULT 0,
    detected_at TIMESTAMP,
    settled_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_blockchain_watch_address_status
    ON financial.blockchain_address_watch(address, status);

CREATE UNIQUE INDEX IF NOT EXISTS idx_blockchain_watch_transfer
    ON financial.blockchain_address_watch(transfer_id);

CREATE INDEX IF NOT EXISTS idx_blockchain_watch_txid
    ON financial.blockchain_address_watch(observed_txid);

-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 020: Auditable network transfer events
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS financial.network_transfer_events (
    id UUID PRIMARY KEY,
    transfer_id UUID REFERENCES financial.network_transfers(id) ON DELETE SET NULL,
    user_id BIGINT REFERENCES auth.users_credentials(id) ON DELETE SET NULL,
    event_type VARCHAR(64) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    reference VARCHAR(255),
    payload TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_network_transfer_events_transfer_created
    ON financial.network_transfer_events(transfer_id, created_at);

CREATE INDEX IF NOT EXISTS idx_network_transfer_events_reference
    ON financial.network_transfer_events(reference);

CREATE INDEX IF NOT EXISTS idx_network_transfer_events_type
    ON financial.network_transfer_events(event_type);

-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 021: Explicit user roles for method-level authorization
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE auth.users_credentials
    ADD COLUMN IF NOT EXISTS role VARCHAR(32) NOT NULL DEFAULT 'USER';

UPDATE auth.users_credentials
    SET role = 'USER'
    WHERE role IS NULL OR role = '';

CREATE INDEX IF NOT EXISTS idx_users_credentials_role
    ON auth.users_credentials(role);

-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 022: Durable payment links
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS financial.payment_links (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT REFERENCES auth.users_credentials(id) ON DELETE SET NULL,
    session_id VARCHAR(128),
    amount_btc NUMERIC(19, 8) NOT NULL,
    gross_amount_btc NUMERIC(19, 8),
    deposit_fee_btc NUMERIC(19, 8),
    net_amount_btc NUMERIC(19, 8),
    description VARCHAR(255),
    deposit_address VARCHAR(128) NOT NULL,
    visibility VARCHAR(32) NOT NULL DEFAULT 'PRIVATE',
    confirmation_mode VARCHAR(32) NOT NULL DEFAULT 'USER_ACTION_REQUIRED',
    amount_locked BOOLEAN NOT NULL DEFAULT TRUE,
    reference_label VARCHAR(64),
    metadata_json TEXT,
    status VARCHAR(32) NOT NULL,
    txid VARCHAR(128),
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_at TIMESTAMP,
    completed_at TIMESTAMP,
    cancelled_at TIMESTAMP,
    cancel_reason VARCHAR(255),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_payment_links_user_created
    ON financial.payment_links(user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_payment_links_session
    ON financial.payment_links(session_id);

CREATE INDEX IF NOT EXISTS idx_payment_links_status_expires
    ON financial.payment_links(status, expires_at);

CREATE INDEX IF NOT EXISTS idx_payment_links_deposit_address
    ON financial.payment_links(deposit_address);

CREATE INDEX IF NOT EXISTS idx_payment_links_txid
    ON financial.payment_links(txid);

ALTER TABLE financial.payment_links
    ALTER COLUMN confirmation_mode SET DEFAULT 'USER_ACTION_REQUIRED';

UPDATE financial.payment_links
    SET confirmation_mode = 'USER_ACTION_REQUIRED'
    WHERE confirmation_mode NOT IN ('USER_ACTION_REQUIRED', 'AUTO_COMPLETE');

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_payment_links_confirmation_mode_self_service'
          AND conrelid = 'financial.payment_links'::regclass
    ) THEN
        ALTER TABLE financial.payment_links
            ADD CONSTRAINT chk_payment_links_confirmation_mode_self_service
            CHECK (confirmation_mode IN ('USER_ACTION_REQUIRED', 'AUTO_COMPLETE')) NOT VALID;
    END IF;
END $$;

-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 023: External provider outbox / saga state
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS financial.external_provider_outbox (
    id UUID PRIMARY KEY,
    transfer_id UUID NOT NULL REFERENCES financial.network_transfers(id) ON DELETE CASCADE,
    operation_type VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    payload_json TEXT,
    provider_reference VARCHAR(255),
    last_error TEXT,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    dispatched_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_external_outbox_status_next
    ON financial.external_provider_outbox(status, next_attempt_at);

CREATE INDEX IF NOT EXISTS idx_external_outbox_transfer
    ON financial.external_provider_outbox(transfer_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_external_outbox_idempotency
    ON financial.external_provider_outbox(idempotency_key);

-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 024: Immutable financial audit event chain
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS financial.financial_audit_events (
    sequence_number BIGSERIAL PRIMARY KEY,
    id UUID NOT NULL UNIQUE,
    event_type VARCHAR(96) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(128),
    user_id BIGINT,
    reference VARCHAR(255),
    payload_hash VARCHAR(64) NOT NULL,
    previous_hash VARCHAR(64) NOT NULL,
    event_hash VARCHAR(64) NOT NULL UNIQUE,
    payload_json TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_financial_audit_sequence
    ON financial.financial_audit_events(sequence_number);

CREATE INDEX IF NOT EXISTS idx_financial_audit_reference
    ON financial.financial_audit_events(reference);

CREATE INDEX IF NOT EXISTS idx_financial_audit_event_type
    ON financial.financial_audit_events(event_type);

CREATE UNIQUE INDEX IF NOT EXISTS idx_financial_audit_hash
    ON financial.financial_audit_events(event_hash);

-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 025: Automatic financial reconciliation runs and issues
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS financial.financial_reconciliation_runs (
    id UUID PRIMARY KEY,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP,
    status VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
    checked_transfers INTEGER NOT NULL DEFAULT 0,
    issue_count INTEGER NOT NULL DEFAULT 0,
    summary TEXT
);

CREATE TABLE IF NOT EXISTS financial.financial_reconciliation_issues (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES financial.financial_reconciliation_runs(id) ON DELETE CASCADE,
    transfer_id UUID REFERENCES financial.network_transfers(id) ON DELETE SET NULL,
    issue_type VARCHAR(96) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    reference VARCHAR(255),
    details TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_reconciliation_issues_run
    ON financial.financial_reconciliation_issues(run_id);

CREATE INDEX IF NOT EXISTS idx_reconciliation_issues_transfer
    ON financial.financial_reconciliation_issues(transfer_id);

CREATE INDEX IF NOT EXISTS idx_reconciliation_issues_status
    ON financial.financial_reconciliation_issues(status);

CREATE INDEX IF NOT EXISTS idx_reconciliation_issues_type
    ON financial.financial_reconciliation_issues(issue_type);

-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 026: Bitcoin Accounts local-first domain
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS financial.bitcoin_accounts (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES auth.users_credentials(id) ON DELETE CASCADE,
    type VARCHAR(48) NOT NULL CHECK (type IN ('INTERNAL_CARD','WATCH_ONLY_COLD_WALLET','LIGHTNING_INTERNAL')),
    custody VARCHAR(48) NOT NULL CHECK (custody IN ('KEROSENE_CUSTODIAL','USER_SELF_CUSTODY','WATCH_ONLY')),
    status VARCHAR(48) NOT NULL CHECK (status IN ('ACTIVE','FROZEN','EXPIRED','REPLACED','SAFETY_LOCKED','USER_ACTION_REQUIRED')),
    label VARCHAR(96) NOT NULL,
    risk_tier VARCHAR(32) NOT NULL DEFAULT 'BRONZE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_bitcoin_accounts_user ON financial.bitcoin_accounts(user_id);
CREATE INDEX IF NOT EXISTS idx_bitcoin_accounts_type_status ON financial.bitcoin_accounts(type, status);

CREATE TABLE IF NOT EXISTS financial.bitcoin_ledger_accounts (
    id UUID PRIMARY KEY,
    version BIGINT DEFAULT 0,
    user_id BIGINT NOT NULL REFERENCES auth.users_credentials(id) ON DELETE CASCADE,
    bitcoin_account_id UUID NOT NULL REFERENCES financial.bitcoin_accounts(id) ON DELETE CASCADE,
    currency VARCHAR(16) NOT NULL DEFAULT 'BTC',
    balance_available_sats BIGINT NOT NULL DEFAULT 0 CHECK (balance_available_sats >= 0),
    balance_pending_sats BIGINT NOT NULL DEFAULT 0 CHECK (balance_pending_sats >= 0),
    balance_locked_sats BIGINT NOT NULL DEFAULT 0 CHECK (balance_locked_sats >= 0),
    balance_auto_hold_sats BIGINT NOT NULL DEFAULT 0 CHECK (balance_auto_hold_sats >= 0),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_bitcoin_ledger_accounts_user ON financial.bitcoin_ledger_accounts(user_id);
CREATE INDEX IF NOT EXISTS idx_bitcoin_ledger_accounts_account ON financial.bitcoin_ledger_accounts(bitcoin_account_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_bitcoin_ledger_accounts_account_unique ON financial.bitcoin_ledger_accounts(bitcoin_account_id);

CREATE TABLE IF NOT EXISTS financial.internal_btc_cards (
    id UUID PRIMARY KEY,
    bitcoin_account_id UUID NOT NULL REFERENCES financial.bitcoin_accounts(id) ON DELETE CASCADE,
    ledger_account_id UUID NOT NULL REFERENCES financial.bitcoin_ledger_accounts(id) ON DELETE CASCADE,
    permanent_address_id UUID,
    receiving_policy VARCHAR(48) NOT NULL DEFAULT 'ROTATING',
    daily_limit_sats BIGINT NOT NULL DEFAULT 0 CHECK (daily_limit_sats >= 0),
    monthly_limit_sats BIGINT NOT NULL DEFAULT 0 CHECK (monthly_limit_sats >= 0),
    expires_at TIMESTAMP,
    status VARCHAR(48) NOT NULL CHECK (status IN ('ACTIVE','FROZEN','EXPIRED','REPLACED','SAFETY_LOCKED')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_internal_btc_cards_account ON financial.internal_btc_cards(bitcoin_account_id);
CREATE INDEX IF NOT EXISTS idx_internal_btc_cards_ledger ON financial.internal_btc_cards(ledger_account_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_internal_btc_cards_account_unique ON financial.internal_btc_cards(bitcoin_account_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_internal_btc_cards_ledger_unique ON financial.internal_btc_cards(ledger_account_id);

CREATE TABLE IF NOT EXISTS financial.receiving_addresses (
    id UUID PRIMARY KEY,
    card_id UUID NOT NULL REFERENCES financial.internal_btc_cards(id) ON DELETE CASCADE,
    address VARCHAR(128) NOT NULL UNIQUE,
    derivation_path VARCHAR(160) NOT NULL,
    derivation_index INTEGER NOT NULL CHECK (derivation_index >= -1),
    script_type VARCHAR(16) NOT NULL CHECK (script_type IN ('P2WPKH','P2TR')),
    status VARCHAR(48) NOT NULL CHECK (status IN ('UNUSED','ASSIGNED','OBSERVED','EXPIRED','EXPIRED_RECEIVED','USER_ACTION_REQUIRED','SAFETY_LOCKED')),
    first_seen_txid VARCHAR(128),
    last_seen_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_receiving_addresses_card ON financial.receiving_addresses(card_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_receiving_addresses_address ON financial.receiving_addresses(address);
CREATE INDEX IF NOT EXISTS idx_receiving_addresses_status ON financial.receiving_addresses(status);

CREATE TABLE IF NOT EXISTS financial.receiving_requests (
    id UUID PRIMARY KEY,
    card_id UUID NOT NULL REFERENCES financial.internal_btc_cards(id) ON DELETE CASCADE,
    address_id UUID NOT NULL REFERENCES financial.receiving_addresses(id) ON DELETE CASCADE,
    public_code VARCHAR(48) NOT NULL UNIQUE CHECK (public_code LIKE 'KRS-%'),
    amount_sats BIGINT CHECK (amount_sats IS NULL OR amount_sats > 0),
    expires_at TIMESTAMP,
    one_time BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(48) NOT NULL CHECK (status IN ('ACTIVE','EXPIRED','MEMPOOL_SEEN','CONFIRMING','PAID','EXPIRED_RECEIVED','AUTO_RESOLUTION_PENDING','USER_ACTION_REQUIRED','HIDDEN','FAILED_SAFE')),
    self_service_reason VARCHAR(160),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_at TIMESTAMP,
    purge_after TIMESTAMP NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '24 hours'),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_receiving_requests_card ON financial.receiving_requests(card_id);
CREATE INDEX IF NOT EXISTS idx_receiving_requests_address ON financial.receiving_requests(address_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_receiving_requests_public_code ON financial.receiving_requests(public_code);
CREATE INDEX IF NOT EXISTS idx_receiving_requests_status_expires ON financial.receiving_requests(status, expires_at);

CREATE TABLE IF NOT EXISTS financial.bitcoin_ledger_entries (
    id UUID PRIMARY KEY,
    ledger_account_id UUID NOT NULL REFERENCES financial.bitcoin_ledger_accounts(id) ON DELETE CASCADE,
    direction VARCHAR(16) NOT NULL CHECK (direction IN ('CREDIT','DEBIT')),
    amount_sats BIGINT NOT NULL CHECK (amount_sats > 0),
    status VARCHAR(32) NOT NULL CHECK (status IN ('PENDING','AVAILABLE','LOCKED','AUTO_HOLD','FINALIZED','REVERSED','FAILED_SAFE')),
    source_type VARCHAR(64) NOT NULL,
    source_id VARCHAR(160) NOT NULL,
    idempotency_key VARCHAR(180) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_bitcoin_ledger_entries_account ON financial.bitcoin_ledger_entries(ledger_account_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_bitcoin_ledger_entries_idempotency ON financial.bitcoin_ledger_entries(idempotency_key);
CREATE INDEX IF NOT EXISTS idx_bitcoin_ledger_entries_source ON financial.bitcoin_ledger_entries(source_type, source_id);

CREATE TABLE IF NOT EXISTS financial.cold_wallets (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES financial.bitcoin_accounts(id) ON DELETE CASCADE,
    descriptor TEXT,
    xpub TEXT,
    fingerprint VARCHAR(32) NOT NULL,
    derivation_path VARCHAR(160) NOT NULL,
    script_policy VARCHAR(32) NOT NULL CHECK (script_policy IN ('SINGLE_SIG','MULTISIG')),
    can_sign BOOLEAN NOT NULL DEFAULT FALSE CHECK (can_sign = FALSE),
    last_scan_height BIGINT NOT NULL DEFAULT 0,
    observed_balance_sats BIGINT NOT NULL DEFAULT 0 CHECK (observed_balance_sats >= 0),
    CHECK ((descriptor IS NOT NULL AND length(trim(descriptor)) > 0) OR (xpub IS NOT NULL AND length(trim(xpub)) > 0)),
    CHECK (fingerprint ~* '^[0-9a-f]{8}$'),
    CHECK (derivation_path LIKE 'm/%'),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_cold_wallets_account ON financial.cold_wallets(account_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_cold_wallets_account_unique ON financial.cold_wallets(account_id);
CREATE INDEX IF NOT EXISTS idx_cold_wallets_fingerprint ON financial.cold_wallets(fingerprint);

CREATE TABLE IF NOT EXISTS financial.cold_wallet_addresses (
    id UUID PRIMARY KEY,
    cold_wallet_id UUID NOT NULL REFERENCES financial.cold_wallets(id) ON DELETE CASCADE,
    address VARCHAR(128) NOT NULL UNIQUE,
    derivation_index INTEGER NOT NULL CHECK (derivation_index >= 0),
    is_change BOOLEAN NOT NULL DEFAULT FALSE,
    observed_balance_sats BIGINT NOT NULL DEFAULT 0 CHECK (observed_balance_sats >= 0),
    last_seen_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_cold_wallet_addresses_wallet ON financial.cold_wallet_addresses(cold_wallet_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_cold_wallet_addresses_address ON financial.cold_wallet_addresses(address);

CREATE TABLE IF NOT EXISTS financial.cold_wallet_utxos (
    id UUID PRIMARY KEY,
    cold_wallet_id UUID NOT NULL REFERENCES financial.cold_wallets(id) ON DELETE CASCADE,
    txid VARCHAR(128) NOT NULL,
    vout INTEGER NOT NULL CHECK (vout >= 0),
    amount_sats BIGINT NOT NULL CHECK (amount_sats > 0),
    confirmations INTEGER NOT NULL DEFAULT 0 CHECK (confirmations >= 0),
    status VARCHAR(32) NOT NULL CHECK (status IN ('UNSPENT','SPENT','LOCKED')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_cold_wallet_utxos_wallet ON financial.cold_wallet_utxos(cold_wallet_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_cold_wallet_utxos_outpoint ON financial.cold_wallet_utxos(txid, vout);
CREATE INDEX IF NOT EXISTS idx_cold_wallet_utxos_status ON financial.cold_wallet_utxos(status);

CREATE TABLE IF NOT EXISTS financial.psbt_workflows (
    id UUID PRIMARY KEY,
    cold_wallet_id UUID NOT NULL REFERENCES financial.cold_wallets(id) ON DELETE CASCADE,
    unsigned_psbt TEXT NOT NULL,
    signed_psbt TEXT,
    destination_outputs_hash VARCHAR(64) NOT NULL CHECK (length(destination_outputs_hash) = 64),
    destination_address VARCHAR(128) NOT NULL,
    amount_sats BIGINT NOT NULL CHECK (amount_sats > 0),
    selected_inputs_hash VARCHAR(64) NOT NULL CHECK (length(selected_inputs_hash) = 64),
    selected_outpoints TEXT NOT NULL DEFAULT '',
    change_output_hash VARCHAR(64) CHECK (change_output_hash IS NULL OR length(change_output_hash) = 64),
    fee_rate BIGINT NOT NULL DEFAULT 0 CHECK (fee_rate >= 0),
    estimated_fee_sats BIGINT NOT NULL DEFAULT 0 CHECK (estimated_fee_sats >= 0),
    status VARCHAR(48) NOT NULL CHECK (status IN ('DRAFT','UNSIGNED_CREATED','WAITING_EXTERNAL_SIGNATURE','SIGNED_SUBMITTED','VALIDATED','REJECTED_TAMPERED','REJECTED_POLICY','BROADCASTED','CONFIRMED','FAILED_SAFE')),
    broadcast_txid VARCHAR(128),
    expires_at TIMESTAMP NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '24 hours'),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_psbt_workflows_wallet ON financial.psbt_workflows(cold_wallet_id);
CREATE INDEX IF NOT EXISTS idx_psbt_workflows_status ON financial.psbt_workflows(status);

CREATE TABLE IF NOT EXISTS financial.tax_events (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES auth.users_credentials(id) ON DELETE CASCADE,
    jurisdiction VARCHAR(32) NOT NULL DEFAULT 'UNSPECIFIED',
    event_type VARCHAR(48) NOT NULL CHECK (event_type IN ('DEPOSIT_INTERNAL','WITHDRAWAL_SELF_CUSTODY','PAYMENT_SPEND','INTERNAL_TRANSFER','LIGHTNING_OPEN','LIGHTNING_CLOSE','FEE_PAID','COLD_WALLET_OBSERVED_IN','COLD_WALLET_OBSERVED_OUT')),
    asset VARCHAR(16) NOT NULL DEFAULT 'BTC',
    quantity_sats BIGINT NOT NULL CHECK (quantity_sats > 0),
    fair_market_value NUMERIC(24, 8),
    fiat_currency VARCHAR(8),
    cost_basis NUMERIC(24, 8),
    acquisition_date TIMESTAMP,
    disposal_date TIMESTAMP,
    source_txid VARCHAR(128),
    account_id UUID,
    card_id UUID,
    wallet_id UUID,
    classification VARCHAR(64) NOT NULL DEFAULT 'USER_CLASSIFICATION_PENDING',
    metadata_redacted TEXT NOT NULL DEFAULT '{}',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    purge_after TIMESTAMP NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '24 hours'),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_tax_events_user_created ON financial.tax_events(user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_tax_events_purge ON financial.tax_events(purge_after);
CREATE INDEX IF NOT EXISTS idx_tax_events_type ON financial.tax_events(event_type);

ALTER TABLE financial.psbt_workflows
    ADD COLUMN IF NOT EXISTS selected_outpoints TEXT NOT NULL DEFAULT '';
ALTER TABLE financial.psbt_workflows
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '24 hours');
ALTER TABLE financial.tax_events
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE TABLE IF NOT EXISTS financial.bitcoin_account_audit_events (
    id UUID PRIMARY KEY,
    actor_type VARCHAR(32) NOT NULL,
    actor_id VARCHAR(128),
    action VARCHAR(96) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id VARCHAR(128) NOT NULL,
    metadata_redacted TEXT NOT NULL DEFAULT '{}',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_bitcoin_account_audit_entity ON financial.bitcoin_account_audit_events(entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_bitcoin_account_audit_created ON financial.bitcoin_account_audit_events(created_at);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_receiving_addresses_derivation_index') THEN
        ALTER TABLE financial.receiving_addresses
            ADD CONSTRAINT chk_receiving_addresses_derivation_index
            CHECK (derivation_index >= -1) NOT VALID;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_receiving_requests_public_code_prefix') THEN
        ALTER TABLE financial.receiving_requests
            ADD CONSTRAINT chk_receiving_requests_public_code_prefix
            CHECK (public_code LIKE 'KRS-%') NOT VALID;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_cold_wallets_public_material') THEN
        ALTER TABLE financial.cold_wallets
            ADD CONSTRAINT chk_cold_wallets_public_material
            CHECK ((descriptor IS NOT NULL AND length(trim(descriptor)) > 0) OR (xpub IS NOT NULL AND length(trim(xpub)) > 0)) NOT VALID;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_cold_wallets_fingerprint_hex') THEN
        ALTER TABLE financial.cold_wallets
            ADD CONSTRAINT chk_cold_wallets_fingerprint_hex
            CHECK (fingerprint ~* '^[0-9a-f]{8}$') NOT VALID;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_cold_wallets_derivation_path') THEN
        ALTER TABLE financial.cold_wallets
            ADD CONSTRAINT chk_cold_wallets_derivation_path
            CHECK (derivation_path LIKE 'm/%') NOT VALID;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_cold_wallet_addresses_derivation_index') THEN
        ALTER TABLE financial.cold_wallet_addresses
            ADD CONSTRAINT chk_cold_wallet_addresses_derivation_index
            CHECK (derivation_index >= 0) NOT VALID;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_cold_wallet_utxos_vout') THEN
        ALTER TABLE financial.cold_wallet_utxos
            ADD CONSTRAINT chk_cold_wallet_utxos_vout
            CHECK (vout >= 0) NOT VALID;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_psbt_workflows_destination_hash') THEN
        ALTER TABLE financial.psbt_workflows
            ADD CONSTRAINT chk_psbt_workflows_destination_hash
            CHECK (length(destination_outputs_hash) = 64) NOT VALID;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_psbt_workflows_inputs_hash') THEN
        ALTER TABLE financial.psbt_workflows
            ADD CONSTRAINT chk_psbt_workflows_inputs_hash
            CHECK (length(selected_inputs_hash) = 64) NOT VALID;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_psbt_workflows_change_hash') THEN
        ALTER TABLE financial.psbt_workflows
            ADD CONSTRAINT chk_psbt_workflows_change_hash
            CHECK (change_output_hash IS NULL OR length(change_output_hash) = 64) NOT VALID;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_tax_events_quantity_positive') THEN
        ALTER TABLE financial.tax_events
            ADD CONSTRAINT chk_tax_events_quantity_positive
            CHECK (quantity_sats > 0) NOT VALID;
    END IF;
END $$;
