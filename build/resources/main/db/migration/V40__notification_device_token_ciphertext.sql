-- Store AES-GCM ciphertext of the device push/local-alert token so a future
-- remote push adapter can decrypt without ever logging plaintext.
-- Hash remains the primary lookup key; raw token is never returned by the API.
ALTER TABLE public.notification_device_tokens
    ADD COLUMN IF NOT EXISTS token_ciphertext TEXT;

COMMENT ON COLUMN public.notification_device_tokens.token_ciphertext IS
    'Optional CosignerSecretService AES-GCM ciphertext of the device token.';
