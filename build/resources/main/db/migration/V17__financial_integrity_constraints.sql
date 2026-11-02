-- NOT VALID keeps legacy pre-alpha rows from blocking startup while still
-- enforcing these invariants for new and updated rows.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_network_transfers_non_negative_amounts'
          AND conrelid = 'financial.network_transfers'::regclass
    ) THEN
        ALTER TABLE financial.network_transfers
            ADD CONSTRAINT chk_network_transfers_non_negative_amounts
            CHECK (
                (expected_amount_btc IS NULL OR expected_amount_btc >= 0)
                AND (amount_btc IS NULL OR amount_btc >= 0)
                AND (network_fee_btc IS NULL OR network_fee_btc >= 0)
                AND (platform_fee_btc IS NULL OR platform_fee_btc >= 0)
                AND (total_debited_btc IS NULL OR total_debited_btc >= 0)
                AND confirmations >= 0
            ) NOT VALID;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_network_transfers_outbound_total_covers_components'
          AND conrelid = 'financial.network_transfers'::regclass
    ) THEN
        ALTER TABLE financial.network_transfers
            ADD CONSTRAINT chk_network_transfers_outbound_total_covers_components
            CHECK (
                transfer_type <> 'OUTBOUND_PAYMENT'
                OR total_debited_btc IS NULL
                OR total_debited_btc >= (
                    COALESCE(amount_btc, 0)
                    + COALESCE(network_fee_btc, 0)
                    + COALESCE(platform_fee_btc, 0)
                )
            ) NOT VALID;
    END IF;

    IF to_regclass('financial.ledger_entries') IS NOT NULL THEN
        IF NOT EXISTS (
            SELECT 1
            FROM pg_constraint
            WHERE conname = 'chk_ledger_entries_non_negative_amounts'
              AND conrelid = 'financial.ledger_entries'::regclass
        ) THEN
            ALTER TABLE financial.ledger_entries
                ADD CONSTRAINT chk_ledger_entries_non_negative_amounts
                CHECK (amount_net >= 0 AND fee_amount >= 0) NOT VALID;
        END IF;

        IF NOT EXISTS (
            SELECT 1
            FROM pg_constraint
            WHERE conname = 'chk_ledger_entries_platform_fee_rows'
              AND conrelid = 'financial.ledger_entries'::regclass
        ) THEN
            ALTER TABLE financial.ledger_entries
                ADD CONSTRAINT chk_ledger_entries_platform_fee_rows
                CHECK (
                    (user_id <> 'PLATFORM' OR amount_net = 0)
                    AND (status <> 'COLLECTED' OR user_id = 'PLATFORM')
                ) NOT VALID;
        END IF;
    END IF;
END $$;
