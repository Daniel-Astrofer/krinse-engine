-- Repair migration for local/docker databases where the active KFE receive
-- derivation cursor table was removed by the legacy financial cleanup.

CREATE TABLE IF NOT EXISTS financial.custodial_derivation_cursors (
    cursor_key VARCHAR(64) PRIMARY KEY,
    last_issued_index INTEGER NOT NULL DEFAULT -1,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
