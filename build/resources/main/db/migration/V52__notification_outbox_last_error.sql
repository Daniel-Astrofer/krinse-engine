ALTER TABLE financial.kfe_financial_notification_outbox
    ADD COLUMN IF NOT EXISTS last_error TEXT;
