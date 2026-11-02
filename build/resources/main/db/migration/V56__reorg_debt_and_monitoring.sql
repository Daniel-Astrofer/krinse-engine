ALTER TABLE financial.balances_core
    ADD COLUMN IF NOT EXISTS reorg_debt_sats BIGINT NOT NULL DEFAULT 0;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_balances_core_reorg_debt_non_negative'
          AND conrelid = 'financial.balances_core'::regclass
    ) THEN
        ALTER TABLE financial.balances_core
            ADD CONSTRAINT chk_balances_core_reorg_debt_non_negative
            CHECK (reorg_debt_sats >= 0);
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_transactions_inbound_reorg_monitor
    ON financial.transactions_master(status, confirmation_monitoring_active, updated_at)
    WHERE rail = 'ONCHAIN'
      AND direction = 'INBOUND'
      AND blockchain_txid IS NOT NULL
      AND blockchain_txid <> '';
