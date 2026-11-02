-- Remove the retired mining marketplace schema without mutating prior applied migrations.
-- V2/V14 must remain immutable for Flyway checksum validation on existing local databases.

DROP TABLE IF EXISTS financial.mining_allocations CASCADE;
DROP TABLE IF EXISTS financial.mining_rig_offers CASCADE;
