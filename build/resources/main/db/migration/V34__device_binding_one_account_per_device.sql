-- One device (device_install_id) may bind to at most one ACTIVE account.
-- One account may still own many devices (no unique on user_id).
-- Legacy cleanup: keep the newest ACTIVE credential per install, delete the rest.

-- Passkeys: drop older ACTIVE rows sharing the same device_install_id
WITH ranked_passkeys AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY device_install_id
               ORDER BY COALESCE(last_access_at, first_access_at) DESC NULLS LAST, id DESC
           ) AS rn
    FROM auth.passkey_credentials
    WHERE device_install_id IS NOT NULL
      AND btrim(device_install_id) <> ''
      AND upper(COALESCE(status, 'ACTIVE')) = 'ACTIVE'
)
DELETE FROM auth.passkey_credentials p
 USING ranked_passkeys r
 WHERE p.id = r.id
   AND r.rn > 1;

-- Device keys: same cleanup
WITH ranked_device_keys AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY device_install_id
               ORDER BY COALESCE(last_used_at, created_at) DESC NULLS LAST, id DESC
           ) AS rn
    FROM auth.device_key_credentials
    WHERE device_install_id IS NOT NULL
      AND btrim(device_install_id) <> ''
      AND upper(COALESCE(status, 'ACTIVE')) = 'ACTIVE'
)
DELETE FROM auth.device_key_credentials d
 USING ranked_device_keys r
 WHERE d.id = r.id
   AND r.rn > 1;

-- Cross-table: if the same install is ACTIVE on both tables for different users,
-- prefer passkey (delete conflicting device-key). Same-user both tables is OK.
DELETE FROM auth.device_key_credentials dk
 WHERE upper(COALESCE(dk.status, 'ACTIVE')) = 'ACTIVE'
   AND dk.device_install_id IS NOT NULL
   AND btrim(dk.device_install_id) <> ''
   AND EXISTS (
       SELECT 1
         FROM auth.passkey_credentials pk
        WHERE pk.device_install_id = dk.device_install_id
          AND upper(COALESCE(pk.status, 'ACTIVE')) = 'ACTIVE'
          AND pk.user_id IS DISTINCT FROM dk.user_id
   );

CREATE UNIQUE INDEX IF NOT EXISTS ux_passkey_active_device_install
    ON auth.passkey_credentials (device_install_id)
    WHERE upper(COALESCE(status, 'ACTIVE')) = 'ACTIVE'
      AND device_install_id IS NOT NULL
      AND btrim(device_install_id) <> '';

CREATE UNIQUE INDEX IF NOT EXISTS ux_device_key_active_device_install
    ON auth.device_key_credentials (device_install_id)
    WHERE upper(COALESCE(status, 'ACTIVE')) = 'ACTIVE'
      AND device_install_id IS NOT NULL
      AND btrim(device_install_id) <> '';
