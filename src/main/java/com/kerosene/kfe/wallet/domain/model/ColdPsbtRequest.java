package com.kerosene.kfe.wallet.domain.model;

import java.util.List;

/**
 * Requested transaction details for a cold-wallet signing workflow.
 *
 * @param destinationAddress external payment destination, validated against the active network
 * @param amountSats payment amount in satoshis before fees
 * @param confirmationTarget optional desired confirmation horizon for fee estimation
 * @param feeRateSatsPerVbyte optional explicit fee rate, in satoshis per virtual byte
 * @param requestedInputs optional caller-requested outpoints; empty means policy may select inputs
 */
public record ColdPsbtRequest(
        String destinationAddress,
        long amountSats,
        Integer confirmationTarget,
        Long feeRateSatsPerVbyte,
        List<Outpoint> requestedInputs) {

    /** Copies the requested-input list and treats null as no explicit input restriction. */
    public ColdPsbtRequest {
        requestedInputs = requestedInputs == null ? List.of() : List.copyOf(requestedInputs);
    }
}
