CREATE TABLE IF NOT EXISTS financial.tax_event_classifications (
    user_id BIGINT NOT NULL REFERENCES auth.users_credentials(id) ON DELETE CASCADE,
    event_id VARCHAR(96) NOT NULL,
    classification VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, event_id)
);

CREATE INDEX IF NOT EXISTS idx_tax_event_classifications_user
    ON financial.tax_event_classifications(user_id);
