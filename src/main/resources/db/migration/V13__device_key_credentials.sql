CREATE TABLE IF NOT EXISTS auth.device_key_credentials (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id BIGINT NOT NULL REFERENCES auth.users_credentials(id) ON DELETE CASCADE,
    credential_id VARCHAR(128) NOT NULL UNIQUE,
    user_handle VARCHAR(255) NOT NULL,
    public_key_ed25519 VARCHAR(128) NOT NULL,
    algorithm VARCHAR(32) NOT NULL DEFAULT 'Ed25519',
    counter BIGINT NOT NULL DEFAULT 0,
    device_name VARCHAR(255),
    device_install_id VARCHAR(128) NOT NULL,
    key_storage VARCHAR(64),
    platform VARCHAR(128),
    browser VARCHAR(128),
    onion_service_id VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMP,
    revoked_at TIMESTAMP,
    protocol_version INTEGER NOT NULL DEFAULT 1,
    brand VARCHAR(255),
    model VARCHAR(255),
    serial_number VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_device_key_user_id
    ON auth.device_key_credentials(user_id);

CREATE INDEX IF NOT EXISTS idx_device_key_install_id
    ON auth.device_key_credentials(device_install_id);

CREATE INDEX IF NOT EXISTS idx_device_key_status
    ON auth.device_key_credentials(status);
