ALTER TABLE financial.payment_requests
    DROP CONSTRAINT IF EXISTS chk_payment_requests_rail;

ALTER TABLE financial.payment_requests
    ADD CONSTRAINT chk_payment_requests_rail
    CHECK (rail IN ('INTERNAL', 'ONCHAIN', 'LIGHTNING'));
