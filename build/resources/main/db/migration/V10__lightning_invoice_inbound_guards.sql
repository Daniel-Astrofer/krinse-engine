ALTER TABLE financial.network_transfers
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);

CREATE UNIQUE INDEX IF NOT EXISTS ux_network_transfers_idempotency_key
    ON financial.network_transfers(idempotency_key)
    WHERE idempotency_key IS NOT NULL AND idempotency_key <> '';

CREATE UNIQUE INDEX IF NOT EXISTS ux_network_transfers_lightning_invoice_payment_hash
    ON financial.network_transfers(payment_hash)
    WHERE network = 'LIGHTNING'
      AND transfer_type = 'INBOUND_INVOICE'
      AND payment_hash IS NOT NULL
      AND payment_hash <> '';
