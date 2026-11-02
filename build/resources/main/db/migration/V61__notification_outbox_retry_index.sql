-- Keep the notification worker's retryable states covered by the due-work index.
-- V49 predates FAILED_RETRYABLE and therefore indexed only PENDING/FAILED.
DROP INDEX IF EXISTS financial.idx_notif_outbox_status_next;

CREATE INDEX IF NOT EXISTS idx_notif_outbox_status_next
    ON financial.kfe_financial_notification_outbox (status, next_attempt_at)
    WHERE status IN ('PENDING', 'FAILED_RETRYABLE', 'PROCESSING');
