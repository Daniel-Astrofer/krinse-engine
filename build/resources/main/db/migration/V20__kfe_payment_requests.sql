CREATE TABLE IF NOT EXISTS financial.payment_requests (
    id UUID PRIMARY KEY,
    public_id VARCHAR(48) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL REFERENCES auth.users_credentials(id) ON DELETE CASCADE,
    wallet_id UUID NOT NULL REFERENCES financial.wallets_core(id) ON DELETE CASCADE,
    address_id UUID REFERENCES financial.wallet_addresses(id) ON DELETE SET NULL,
    address VARCHAR(128) NOT NULL,
    rail VARCHAR(32) NOT NULL DEFAULT 'ONCHAIN',
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    amount_sats BIGINT,
    description VARCHAR(180),
    memo VARCHAR(255),
    payer_hint VARCHAR(120),
    paid_transaction_id UUID REFERENCES financial.transactions_master(id) ON DELETE SET NULL,
    expires_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    hidden_at TIMESTAMP,
    cancelled_at TIMESTAMP,
    CONSTRAINT chk_payment_requests_rail
        CHECK (rail IN ('ONCHAIN', 'LIGHTNING')),
    CONSTRAINT chk_payment_requests_status
        CHECK (status IN ('OPEN', 'PAID', 'EXPIRED', 'HIDDEN', 'CANCELLED')),
    CONSTRAINT chk_payment_requests_amount
        CHECK (amount_sats IS NULL OR amount_sats > 0)
);

CREATE INDEX IF NOT EXISTS idx_payment_requests_user_created
    ON financial.payment_requests(user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_payment_requests_public_id
    ON financial.payment_requests(public_id);

CREATE INDEX IF NOT EXISTS idx_payment_requests_wallet_status
    ON financial.payment_requests(wallet_id, status, created_at DESC);
