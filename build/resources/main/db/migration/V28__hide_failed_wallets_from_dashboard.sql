CREATE OR REPLACE VIEW financial.wallet_dashboard_view AS
SELECT
    w.id AS wallet_id,
    w.user_id,
    w.kind,
    w.status,
    w.label,
    w.asset,
    w.spendable,
    COALESCE(b.available_sats, 0) AS available_sats,
    COALESCE(b.pending_sats, 0) AS pending_sats,
    COALESCE(b.locked_sats, 0) AS locked_sats,
    COALESCE(b.auto_hold_sats, 0) AS auto_hold_sats,
    COALESCE(b.observed_sats, 0) AS observed_sats,
    (
        SELECT a.address
        FROM financial.wallet_addresses a
        WHERE a.wallet_id = w.id AND a.status = 'ACTIVE'
        ORDER BY a.created_at DESC
        LIMIT 1
    ) AS active_address,
    w.created_at,
    w.updated_at
FROM financial.wallets_core w
LEFT JOIN financial.balances_core b
    ON b.wallet_id = w.id AND b.asset = w.asset
WHERE w.status IN ('CREATING', 'ACTIVE', 'FROZEN', 'ROTATING_ADDRESS');
