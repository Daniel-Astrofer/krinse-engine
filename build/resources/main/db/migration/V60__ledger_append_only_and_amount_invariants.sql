-- K2 ledger hardening: movements are immutable postings, never mutable balance state.
ALTER TABLE financial.balance_movements
    ADD CONSTRAINT chk_balance_movements_positive_amount
    CHECK (amount_sats > 0) NOT VALID;

CREATE OR REPLACE FUNCTION financial.prevent_balance_movement_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'balance_movements is append-only. Updates and deletes are prohibited.';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS enforce_append_only_balance_movements
    ON financial.balance_movements;

CREATE TRIGGER enforce_append_only_balance_movements
BEFORE UPDATE OR DELETE ON financial.balance_movements
FOR EACH ROW
EXECUTE FUNCTION financial.prevent_balance_movement_modification();

CREATE INDEX IF NOT EXISTS idx_balance_movements_operation_type
    ON financial.balance_movements(transaction_id, movement_type, created_at);

ALTER TABLE financial.user_statement_24h
    ADD CONSTRAINT chk_user_statement_24h_expiry_after_creation
    CHECK (expires_at > created_at) NOT VALID;
