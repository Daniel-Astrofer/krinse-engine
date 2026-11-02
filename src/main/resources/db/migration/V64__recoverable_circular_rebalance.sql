-- Existing in-progress executions have unknown submission/reservation state: never replay them.
ALTER TABLE financial.channel_rebalance_jobs
    ADD COLUMN execution_phase VARCHAR(32) NOT NULL DEFAULT 'READY',
    ADD COLUMN fee_wallet_id UUID,
    ADD COLUMN reserved_fee_sats BIGINT NOT NULL DEFAULT 0 CHECK (reserved_fee_sats >= 0),
    ADD COLUMN payment_hash VARCHAR(64),
    ADD COLUMN actual_fee_sats BIGINT CHECK (actual_fee_sats >= 0),
    ADD CONSTRAINT ck_rebalance_execution_phase CHECK (execution_phase IN ('READY', 'RECONCILING', 'SETTLED'));
UPDATE financial.channel_rebalance_jobs SET execution_phase = 'RECONCILING',
    last_error = 'Legacy execution: reconcile provider and ledger manually; replay prohibited'
WHERE status = 'IN_PROGRESS';
CREATE UNIQUE INDEX uq_rebalance_payment_hash ON financial.channel_rebalance_jobs(payment_hash)
    WHERE payment_hash IS NOT NULL;
