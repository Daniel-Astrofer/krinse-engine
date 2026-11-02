-- Platform Lightning outbound liquidity reservations (held until HTLC terminal success/fail).
CREATE TABLE IF NOT EXISTS financial.lightning_liquidity_reservations (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL UNIQUE,
    amount_sats BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    released_at TIMESTAMP,
    CONSTRAINT chk_ln_liq_res_amount CHECK (amount_sats > 0),
    CONSTRAINT chk_ln_liq_res_status CHECK (status IN ('HELD', 'RELEASED', 'CONSUMED'))
);

CREATE INDEX IF NOT EXISTS idx_ln_liq_res_status
    ON financial.lightning_liquidity_reservations (status)
    WHERE status = 'HELD';
