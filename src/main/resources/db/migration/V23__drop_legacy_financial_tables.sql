-- KEROSENE DEV/TEST RESET MIGRATION
-- This migration intentionally drops legacy financial tables after the KFE schema split.
-- Current data is dev/test only, so destructive reset is acceptable for disposable environments.
-- Do not run against production data unless a separate production migration/backfill plan is approved.

DROP TABLE IF EXISTS financial.payment_execution_claims CASCADE;
DROP TABLE IF EXISTS financial.payment_execution_outbox CASCADE;
DROP TABLE IF EXISTS financial.external_provider_outbox CASCADE;
DROP TABLE IF EXISTS financial.payment_audit_events CASCADE;
DROP TABLE IF EXISTS financial.payment_intents CASCADE;
DROP TABLE IF EXISTS financial.payment_links CASCADE;

DROP TABLE IF EXISTS financial.ledger_entries CASCADE;
DROP TABLE IF EXISTS financial.ledger_transactions CASCADE;
DROP TABLE IF EXISTS financial.ledger_transaction_history CASCADE;
DROP TABLE IF EXISTS financial.ledger CASCADE;
DROP TABLE IF EXISTS financial.wallets CASCADE;

DROP TABLE IF EXISTS financial.network_transfer_events CASCADE;
DROP TABLE IF EXISTS financial.network_transfers CASCADE;
DROP TABLE IF EXISTS financial.receiving_methods CASCADE;
DROP TABLE IF EXISTS financial.receiving_requests CASCADE;
DROP TABLE IF EXISTS financial.receiving_addresses CASCADE;

DROP TABLE IF EXISTS financial.bitcoin_account_audit_events CASCADE;
DROP TABLE IF EXISTS financial.bitcoin_ledger_entries CASCADE;
DROP TABLE IF EXISTS financial.bitcoin_ledger_accounts CASCADE;
DROP TABLE IF EXISTS financial.bitcoin_accounts CASCADE;
DROP TABLE IF EXISTS financial.internal_btc_cards CASCADE;

DROP TABLE IF EXISTS financial.cold_wallet_utxos CASCADE;
DROP TABLE IF EXISTS financial.cold_wallet_addresses CASCADE;
DROP TABLE IF EXISTS financial.cold_wallets CASCADE;
DROP TABLE IF EXISTS financial.psbt_workflows CASCADE;
DROP TABLE IF EXISTS financial.tax_events CASCADE;

DROP TABLE IF EXISTS financial.treasury_payout_requests CASCADE;
DROP TABLE IF EXISTS financial.treasury_config CASCADE;
DROP TABLE IF EXISTS financial.platform_revenue CASCADE;
DROP TABLE IF EXISTS financial.financial_audit_events CASCADE;
DROP TABLE IF EXISTS financial.financial_reconciliation_issues CASCADE;
DROP TABLE IF EXISTS financial.financial_reconciliation_runs CASCADE;
DROP TABLE IF EXISTS financial.merkle_audit CASCADE;
DROP TABLE IF EXISTS financial.siphon_requests CASCADE;
DROP TABLE IF EXISTS financial.processed_transactions CASCADE;
DROP TABLE IF EXISTS financial.blockchain_address_watch CASCADE;
DROP TABLE IF EXISTS financial.custodial_derivation_cursors CASCADE;
