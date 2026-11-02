-- CHANNELS→LND inject durability: stable Intent id, phase, LND funding bind.
ALTER TABLE financial.channel_operation_decisions
    ADD COLUMN IF NOT EXISTS mesh_intent_id VARCHAR(160),
    ADD COLUMN IF NOT EXISTS mesh_inject_phase VARCHAR(40),
    ADD COLUMN IF NOT EXISTS lnd_funding_address VARCHAR(128),
    ADD COLUMN IF NOT EXISTS mesh_fund_txid VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_channel_op_mesh_phase
    ON financial.channel_operation_decisions (mesh_inject_phase, created_at DESC)
    WHERE mesh_inject_phase IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_channel_op_peer_open_resume
    ON financial.channel_operation_decisions (peer_pubkey, amount_sats, created_at DESC)
    WHERE operation = 'OPEN' AND executed = FALSE;
