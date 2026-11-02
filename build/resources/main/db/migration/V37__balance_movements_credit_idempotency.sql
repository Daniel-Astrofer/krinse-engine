-- Idempotent ledger credits: concurrent monitors (PR / custodial / inbound / fee)
-- must not insert two AVAILABLE-side movements for the same transaction.
--
-- Cleanup: keep the oldest row per (transaction_id, movement_type) for credit types.

DELETE FROM financial.balance_movements bm
USING financial.balance_movements newer
WHERE bm.transaction_id IS NOT NULL
  AND bm.transaction_id = newer.transaction_id
  AND bm.movement_type = newer.movement_type
  AND bm.movement_type IN (
      'CREDIT_INBOUND',
      'CREDIT_PAYMENT_REQUEST',
      'CREDIT_CUSTODIAL_DEPOSIT',
      'CREDIT',
      'CREDIT_KEROSENE_FEE'
  )
  AND (
      bm.created_at > newer.created_at
      OR (bm.created_at = newer.created_at AND bm.id > newer.id)
  );

CREATE UNIQUE INDEX IF NOT EXISTS uq_balance_movements_tx_credit_type
    ON financial.balance_movements (transaction_id, movement_type)
    WHERE transaction_id IS NOT NULL
      AND movement_type IN (
          'CREDIT_INBOUND',
          'CREDIT_PAYMENT_REQUEST',
          'CREDIT_CUSTODIAL_DEPOSIT',
          'CREDIT',
          'CREDIT_KEROSENE_FEE'
      );
