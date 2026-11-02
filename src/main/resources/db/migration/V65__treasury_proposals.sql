CREATE TABLE financial.treasury_proposals (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    policy_version VARCHAR(128) NOT NULL,
    evidence_hash VARCHAR(64) NOT NULL,
    evidence_json TEXT NOT NULL,
    plan_json TEXT NOT NULL
);
CREATE INDEX idx_treasury_proposal_history ON financial.treasury_proposals(created_at DESC);
