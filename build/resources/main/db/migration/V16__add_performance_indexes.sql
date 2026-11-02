-- Add performance indexes for high-volume WalletEntity queries
CREATE INDEX IF NOT EXISTS idx_wallet_destination_hash ON financial.wallets (destination_hash);
CREATE INDEX IF NOT EXISTS idx_wallet_lightning_address ON financial.wallets (lightning_address);
CREATE INDEX IF NOT EXISTS idx_wallet_deposit_address ON financial.wallets (deposit_address);
CREATE INDEX IF NOT EXISTS idx_wallet_passphrase_hash ON financial.wallets (address);

-- PaymentIntents
DROP INDEX IF EXISTS idx_payment_intents_status;
CREATE INDEX IF NOT EXISTS idx_payment_intents_status_updated ON financial.payment_intents (status, updated_at);

-- PaymentExecutionOutbox
DROP INDEX IF EXISTS idx_payment_execution_status_next;
CREATE INDEX IF NOT EXISTS idx_payment_execution_status_next ON financial.payment_execution_outbox (status, next_attempt_at, created_at);
