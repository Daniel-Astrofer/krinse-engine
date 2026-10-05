ALTER TABLE financial.mining_allocations
    DROP CONSTRAINT IF EXISTS mining_allocations_wallet_id_fkey;

ALTER TABLE financial.mining_allocations
    ALTER COLUMN wallet_id DROP NOT NULL;

ALTER TABLE financial.mining_allocations
    ALTER COLUMN wallet_id TYPE UUID USING NULL;

ALTER TABLE financial.mining_allocations
    ADD CONSTRAINT mining_allocations_wallet_id_fkey FOREIGN KEY (wallet_id) REFERENCES financial.wallets_core(id) ON DELETE CASCADE;
