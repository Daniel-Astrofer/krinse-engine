package com.kerosene.kfe.adapters.in.http.dto.wallet;

/** Wallet API representation of one unspent transaction output available for transaction construction.
 * The outpoint is the pair {@code txid:vout}; value and confirmation depth support fee selection
 * and spendability decisions, while script and address provide the locking destination.
 *
 * @param txid transaction hash containing this output
 * @param vout zero-based output index within the transaction
 * @param valueSats spendable output value in satoshis
 * @param scriptPubKey locking script encoded as hexadecimal text
 * @param address address associated with the locking script, if one can be decoded
 * @param confirmations number of confirmations reported by the wallet; zero means unconfirmed or unknown
 */
public record KfeUtxoResponse(
        /** Transaction identifier of the funding transaction. */
        String txid,
        /** Zero-based output number identifying this UTXO within {@link #txid}. */
        int vout,
        /** Output value in satoshis. */
        long valueSats,
        /** Hex-encoded locking script for the output. */
        String scriptPubKey,
        /** Human-readable destination address when available. */
        String address,
        /** Confirmation count; zero is used by the compatibility constructor and unconfirmed outputs. */
        int confirmations) {
    /**
     * Compatibility constructor for callers that do not yet provide confirmation data.
     * Such entries are represented with a zero confirmation count.
     *
     * @param txid transaction identifier
     * @param vout zero-based output index
     * @param valueSats output value in satoshis
     * @param scriptPubKey output locking script as hexadecimal text
     * @param address decoded destination address, if available
     */
    public KfeUtxoResponse(
            String txid,
            int vout,
            long valueSats,
            String scriptPubKey,
            String address) {
        this(txid, vout, valueSats, scriptPubKey, address, 0);
    }
}
