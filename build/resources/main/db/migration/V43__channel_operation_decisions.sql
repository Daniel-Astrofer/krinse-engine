-- Lightning channel lifecycle decisions (binary AND gate outcomes, forensic).
CREATE TABLE IF NOT EXISTS financial.channel_operation_decisions (
    id UUID PRIMARY KEY,
    operation VARCHAR(32) NOT NULL,
    passed BOOLEAN NOT NULL,
    peer_pubkey VARCHAR(128),
    channel_point VARCHAR(128),
    amount_sats BIGINT,
    flags_json TEXT NOT NULL,
    decision_reason VARCHAR(255),
    executed BOOLEAN NOT NULL DEFAULT FALSE,
    provider_reference VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_channel_op_type
        CHECK (operation IN ('OPEN', 'REBALANCE', 'CLOSE', 'PPM_ADJUST'))
);

CREATE INDEX IF NOT EXISTS idx_channel_op_created
    ON financial.channel_operation_decisions (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_channel_op_type_created
    ON financial.channel_operation_decisions (operation, created_at DESC);
