package com.kerosene.kfe.wallet.domain.model;

/**
 * Identifies one transaction output by transaction ID and output index.
 *
 * @param txid transaction hash, normalized to trimmed lowercase text
 * @param vout zero-based output position inside the transaction; must be non-negative
 */
public record Outpoint(String txid, int vout) {
    /** Validates and canonicalizes this outpoint identifier. */
    public Outpoint {
        if (txid == null || txid.isBlank()) {
            throw new IllegalArgumentException("UTXO txid is required.");
        }
        if (vout < 0) {
            throw new IllegalArgumentException("UTXO vout cannot be negative.");
        }
        txid = txid.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
