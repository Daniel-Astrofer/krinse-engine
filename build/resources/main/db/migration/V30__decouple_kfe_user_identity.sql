-- KFE runs as a separate runtime in local-full and treats Core user ids as an
-- external identity. SQL foreign keys to auth.users_credentials make remote KFE
-- calls fail before the Core signup transaction is visible to KFE's connection.

DO $$
BEGIN
    IF to_regclass('financial.wallets_core') IS NOT NULL THEN
        ALTER TABLE financial.wallets_core DROP CONSTRAINT IF EXISTS wallets_core_user_id_fkey;
    END IF;

    IF to_regclass('financial.transactions_master') IS NOT NULL THEN
        ALTER TABLE financial.transactions_master DROP CONSTRAINT IF EXISTS transactions_master_user_id_fkey;
    END IF;

    IF to_regclass('financial.transaction_idempotency') IS NOT NULL THEN
        ALTER TABLE financial.transaction_idempotency DROP CONSTRAINT IF EXISTS transaction_idempotency_user_id_fkey;
    END IF;

    IF to_regclass('financial.user_statement_24h') IS NOT NULL THEN
        ALTER TABLE financial.user_statement_24h DROP CONSTRAINT IF EXISTS user_statement_24h_user_id_fkey;
    END IF;

    IF to_regclass('financial.payment_requests') IS NOT NULL THEN
        ALTER TABLE financial.payment_requests DROP CONSTRAINT IF EXISTS payment_requests_user_id_fkey;
    END IF;

    IF to_regclass('financial.kfe_psbt_workflows') IS NOT NULL THEN
        ALTER TABLE financial.kfe_psbt_workflows DROP CONSTRAINT IF EXISTS kfe_psbt_workflows_user_id_fkey;
    END IF;

    IF to_regclass('financial.tax_event_classifications') IS NOT NULL THEN
        ALTER TABLE financial.tax_event_classifications DROP CONSTRAINT IF EXISTS tax_event_classifications_user_id_fkey;
    END IF;
END $$;
