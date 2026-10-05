ALTER TABLE financial.financial_reconciliation_issues
    ADD COLUMN IF NOT EXISTS resolution_status VARCHAR(32) NOT NULL DEFAULT 'PENDING';

ALTER TABLE financial.financial_reconciliation_issues
    ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMP;

ALTER TABLE financial.financial_reconciliation_issues
    ADD COLUMN IF NOT EXISTS resolved_by VARCHAR(128);

ALTER TABLE financial.financial_reconciliation_issues
    ADD COLUMN IF NOT EXISTS resolution_note TEXT;
