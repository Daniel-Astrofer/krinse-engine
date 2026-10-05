ALTER TABLE financial.transactions_master
    ADD COLUMN IF NOT EXISTS external_reference TEXT,
    ADD COLUMN IF NOT EXISTS memo VARCHAR(255);
