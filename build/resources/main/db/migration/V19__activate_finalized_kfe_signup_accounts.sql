-- Accounts that completed passkey onboarding and already have an active
-- spendable KFE internal wallet are finalized accounts. Older signup code left
-- them inactive, which made receiving-capabilities return RECEIVER_NOT_READY.
UPDATE auth.users_credentials u
SET is_active = true,
    activated_at = COALESCE(u.activated_at, CURRENT_TIMESTAMP),
    updated_at = CURRENT_TIMESTAMP
WHERE COALESCE(u.is_active, false) = false
  AND EXISTS (
      SELECT 1
      FROM auth.passkey_credentials p
      WHERE p.user_id = u.id
        AND p.status = 'ACTIVE'
  )
  AND EXISTS (
      SELECT 1
      FROM financial.wallets_core w
      WHERE w.user_id = u.id
        AND w.kind = 'INTERNAL'
        AND w.status = 'ACTIVE'
        AND w.spendable = true
  );
