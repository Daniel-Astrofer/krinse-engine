package com.kerosene.kfe.wallet.application.result;

import com.kerosene.kfe.wallet.domain.model.Outpoint;

import java.util.List;
import java.util.UUID;

/**
 * Result needed to deliver an unsigned transaction to an offline signer.
 *
 * @param workflowId durable workflow identity used to submit or inspect signing progress
 * @param psbt unsigned transaction payload
 * @param psbtHash digest binding the workflow to the exact PSBT bytes
 * @param feeSats selected network fee in satoshis
 * @param amountSats payment amount in satoshis, excluding the network fee
 * @param destinationAddress validated payment destination
 * @param inputs exact wallet outpoints selected for this transaction
 */
public record ColdPsbtResult(
        UUID workflowId,
        String psbt,
        String psbtHash,
        long feeSats,
        long amountSats,
        String destinationAddress,
        List<Outpoint> inputs) {
    /** Normalizes absent inputs and prevents callers from mutating the returned selection. */
    public ColdPsbtResult {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
    }
}
