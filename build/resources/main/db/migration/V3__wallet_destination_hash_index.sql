ALTER TABLE financial.wallets
    ADD COLUMN IF NOT EXISTS destination_hash VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS idx_wallet_destination_hash
    ON financial.wallets(destination_hash)
    WHERE destination_hash IS NOT NULL;
