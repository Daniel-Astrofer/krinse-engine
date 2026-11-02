CREATE TABLE IF NOT EXISTS financial.financial_audit_lock (
    id SMALLINT PRIMARY KEY,
    lock_name VARCHAR(64) NOT NULL UNIQUE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO financial.financial_audit_lock (id, lock_name)
VALUES (1, 'KFE_AUDIT_APPEND')
ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS financial.custodial_derivation_cursors (
    cursor_key VARCHAR(64) PRIMARY KEY,
    last_issued_index INTEGER NOT NULL DEFAULT -1,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
