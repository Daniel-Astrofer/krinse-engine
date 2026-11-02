-- Add rails_data column to store multi-rail receiving payloads (JSON array).
ALTER TABLE financial.payment_requests
    ADD COLUMN IF NOT EXISTS rails_data TEXT;
