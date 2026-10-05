ALTER TABLE financial.wallets_core
    DROP CONSTRAINT IF EXISTS chk_wallets_core_kind;

ALTER TABLE financial.wallets_core
    ADD CONSTRAINT chk_wallets_core_kind
        CHECK (kind IN ('INTERNAL', 'CUSTODIAL_ONCHAIN', 'WATCH_ONLY', 'SYSTEM_FUNDS', 'SYSTEM_PROFIT'));

CREATE UNIQUE INDEX IF NOT EXISTS ux_wallets_core_system_funds_active
ON financial.wallets_core (user_id, kind)
WHERE kind = 'SYSTEM_FUNDS'
  AND status IN ('CREATING', 'ACTIVE', 'FROZEN', 'ROTATING_ADDRESS');

CREATE UNIQUE INDEX IF NOT EXISTS ux_wallets_core_system_profit_active
ON financial.wallets_core (user_id, kind)
WHERE kind = 'SYSTEM_PROFIT'
  AND status IN ('CREATING', 'ACTIVE', 'FROZEN', 'ROTATING_ADDRESS');
