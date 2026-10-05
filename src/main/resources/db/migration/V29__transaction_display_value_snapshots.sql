ALTER TABLE financial.transactions_master
    ADD COLUMN IF NOT EXISTS display_btc_usd NUMERIC(19, 8),
    ADD COLUMN IF NOT EXISTS display_btc_eur NUMERIC(19, 8),
    ADD COLUMN IF NOT EXISTS display_btc_brl NUMERIC(19, 8),
    ADD COLUMN IF NOT EXISTS display_amount_usd NUMERIC(19, 2),
    ADD COLUMN IF NOT EXISTS display_amount_eur NUMERIC(19, 2),
    ADD COLUMN IF NOT EXISTS display_amount_brl NUMERIC(19, 2);
