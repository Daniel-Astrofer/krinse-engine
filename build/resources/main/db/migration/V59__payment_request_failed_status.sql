-- FAILED is a terminal operational state already exposed by the domain model.
-- Keep the database constraint aligned so provider failures can be persisted.
ALTER TABLE financial.payment_requests
    DROP CONSTRAINT IF EXISTS chk_payment_requests_status;

ALTER TABLE financial.payment_requests
    ADD CONSTRAINT chk_payment_requests_status
    CHECK (status IN ('OPEN', 'PAID', 'EXPIRED', 'HIDDEN', 'CANCELLED', 'FAILED'));
