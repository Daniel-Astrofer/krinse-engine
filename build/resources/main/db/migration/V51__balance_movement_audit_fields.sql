ALTER TABLE financial.balance_movements
    ADD COLUMN IF NOT EXISTS reason VARCHAR(128),
    ADD COLUMN IF NOT EXISTS correlation_id UUID,
    ADD COLUMN IF NOT EXISTS causation_id UUID;
