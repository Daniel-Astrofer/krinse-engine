-- Ajusta limites de custodia em bases que ja rodaram a regra antiga:
-- INTERNAL e CUSTODIAL_ONCHAIN continuam unicas; WATCH_ONLY aceita ate duas carteiras ativas.
DROP INDEX IF EXISTS financial.ux_wallets_core_user_kind_active_custody;

WITH ranked_single_custody_wallets AS (
    SELECT
        id,
        ROW_NUMBER() OVER (
            PARTITION BY user_id, kind
            ORDER BY updated_at DESC, created_at DESC, id DESC
        ) AS active_rank
    FROM financial.wallets_core
    WHERE kind IN ('INTERNAL', 'CUSTODIAL_ONCHAIN')
      AND status IN ('CREATING', 'ACTIVE', 'FROZEN', 'ROTATING_ADDRESS')
)
UPDATE financial.wallets_core wallet
SET status = 'ARCHIVED',
    updated_at = CURRENT_TIMESTAMP
FROM ranked_single_custody_wallets ranked
WHERE wallet.id = ranked.id
  AND ranked.active_rank > 1;

WITH ranked_cold_wallets AS (
    SELECT
        id,
        ROW_NUMBER() OVER (
            PARTITION BY user_id
            ORDER BY updated_at DESC, created_at DESC, id DESC
        ) AS active_rank
    FROM financial.wallets_core
    WHERE kind = 'WATCH_ONLY'
      AND status IN ('CREATING', 'ACTIVE', 'FROZEN', 'ROTATING_ADDRESS')
)
UPDATE financial.wallets_core wallet
SET status = 'ARCHIVED',
    updated_at = CURRENT_TIMESTAMP
FROM ranked_cold_wallets ranked
WHERE wallet.id = ranked.id
  AND ranked.active_rank > 2;

CREATE UNIQUE INDEX IF NOT EXISTS ux_wallets_core_user_kind_active_custody
ON financial.wallets_core (user_id, kind)
WHERE kind IN ('INTERNAL', 'CUSTODIAL_ONCHAIN')
  AND status IN ('CREATING', 'ACTIVE', 'FROZEN', 'ROTATING_ADDRESS');
