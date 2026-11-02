ALTER TABLE financial.internal_btc_cards
    ADD COLUMN IF NOT EXISTS security_code_ciphertext TEXT;

CREATE INDEX IF NOT EXISTS idx_internal_btc_cards_permanent_address
    ON financial.internal_btc_cards(permanent_address_id);

UPDATE financial.internal_btc_cards
SET expires_at = COALESCE(expires_at, created_at + INTERVAL '4 years')
WHERE expires_at IS NULL;
