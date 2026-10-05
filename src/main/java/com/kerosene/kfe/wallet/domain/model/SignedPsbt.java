package com.kerosene.kfe.wallet.domain.model;

import java.util.List;

/**
 * Parsed transaction facts used to bind a signed PSBT back to its approved request.
 *
 * @param inputs outpoints consumed by the signed transaction
 * @param outputs destination/value pairs produced by the signed transaction
 */
public record SignedPsbt(List<Outpoint> inputs, List<Output> outputs) {
    /** Copies both collections and normalizes null to an empty collection. */
    public SignedPsbt {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
    }

    /**
     * One transaction output as decoded from the signed PSBT.
     *
     * @param address destination address encoded in the output script
     * @param valueSats output value in satoshis
     */
    public record Output(String address, long valueSats) {
    }
}
