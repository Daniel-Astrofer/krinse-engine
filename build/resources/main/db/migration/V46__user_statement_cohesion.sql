-- Cohesive 24h statement: one row per (user, transaction).
-- Preserve oldest created_at so history order stays fixed when status updates.

DELETE FROM financial.user_statement_24h older
USING financial.user_statement_24h newer
WHERE older.user_id = newer.user_id
  AND older.transaction_id = newer.transaction_id
  AND (
      older.created_at > newer.created_at
      OR (older.created_at = newer.created_at AND older.id > newer.id)
  );

ALTER TABLE financial.user_statement_24h
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;

UPDATE financial.user_statement_24h
SET updated_at = COALESCE(updated_at, created_at)
WHERE updated_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_user_statement_24h_user_tx
    ON financial.user_statement_24h (user_id, transaction_id);
