package com.kerosene.kfe.adapters.in.http.dto.wallet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Validated request for constructing a PSBT that spends from a cold wallet.
 * @param destinationAddress external Bitcoin address receiving the requested amount
 * @param amountSats amount to send in satoshis; the validation minimum is the dust threshold
 * @param confirmationTarget desired confirmation count used to select a fee rate
 * @param feeRateSatsPerVbyte explicit fee-rate override, when provided
 * @param inputs optional UTXOs the caller asks to include in the PSBT
 * @param totpCode optional one-time password required by step-up policy
 */
public record KfeColdWalletPsbtRequest(
        @NotBlank @Size(max = 128) String destinationAddress,
        @Min(546) long amountSats,
        @Min(1) Integer confirmationTarget,
        @Min(1) Long feeRateSatsPerVbyte,
        @Valid List<Input> inputs,
        String totpCode) {

    /** Backward-compatible constructor without a TOTP factor.
     * @param destinationAddress external Bitcoin destination
     * @param amountSats requested transfer amount in satoshis
     * @param confirmationTarget desired confirmation count
     * @param feeRateSatsPerVbyte explicit fee rate override
     * @param inputs optional selected funding outputs
     */
    public KfeColdWalletPsbtRequest(
            String destinationAddress,
            long amountSats,
            Integer confirmationTarget,
            Long feeRateSatsPerVbyte,
            List<Input> inputs) {
        this(destinationAddress, amountSats, confirmationTarget, feeRateSatsPerVbyte, inputs, null);
    }

    /** UTXO output selected to fund a cold-wallet PSBT.
     * @param txid transaction ID containing the output
     * @param vout zero-based output index
     */
    public record Input(
            @NotBlank @Size(max = 128) String txid,
            @Min(0) int vout) {
    }
}
