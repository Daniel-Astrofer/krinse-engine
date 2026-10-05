CREATE TABLE IF NOT EXISTS public.notification_device_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    platform VARCHAR(32) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    token_ref VARCHAR(32) NOT NULL,
    device_ref VARCHAR(32),
    app_version VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_notification_device_user
    ON public.notification_device_tokens(user_id);

CREATE INDEX IF NOT EXISTS idx_notification_device_hash
    ON public.notification_device_tokens(token_hash);

CREATE INDEX IF NOT EXISTS idx_notification_device_active
    ON public.notification_device_tokens(user_id, platform, revoked_at);
