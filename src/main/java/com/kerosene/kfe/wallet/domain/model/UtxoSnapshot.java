package com.kerosene.kfe.wallet.domain.model;

/**
 * Immutable chain observation of one unspent transaction output.
 *
 * @param outpoint unique transaction output identity
 * @param valueSats positive output value in satoshis
 * @param scriptPubKey locking script in hexadecimal form when supplied by the chain adapter
 * @param address address associated with the output when it can be decoded
 * @param confirmations observed confirmation count, normalized to at least zero
 */
public record UtxoSnapshot(
        Outpoint outpoint,
        long valueSats,
        String scriptPubKey,
        String address,
        int confirmations) {

    /** Requires a positive value and identity, and clamps negative confirmation counts to zero. */
    public UtxoSnapshot {
        if (outpoint == null || valueSats <= 0) {
            throw new IllegalArgumentException("A UTXO must have a positive value and outpoint.");
        }
        confirmations = Math.max(0, confirmations);
    }
}
