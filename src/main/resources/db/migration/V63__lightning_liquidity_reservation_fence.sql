ALTER TABLE financial.lightning_liquidity_reservations
    ADD COLUMN IF NOT EXISTS row_version BIGINT NOT NULL DEFAULT 0;
