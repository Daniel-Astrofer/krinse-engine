package com.kerosene.kfe.wallet.application.port.out;

import com.kerosene.kfe.wallet.domain.model.Outpoint;

import java.util.List;

/** Outbound boundary for constructing a funded, unsigned Bitcoin PSBT. */
public interface WalletPsbtPort {
    /**
     * Builds a PSBT from the explicitly selected inputs and payment/change parameters.
     *
     * @param inputs exact wallet outpoints permitted for this transaction
     * @param destinationAddress validated external recipient
     * @param amountSats payment value in satoshis, excluding the network fee
     * @param confirmationTarget optional target block count used to estimate a fee rate
     * @param feeRateSatsPerVbyte optional explicit fee rate; takes precedence when supported
     * @param changeAddress wallet-controlled address for any remaining input value
     * @return unsigned PSBT and the fee selected by the chain adapter
     */
    FundedPsbt create(
            List<Outpoint> inputs,
            String destinationAddress,
            long amountSats,
            Integer confirmationTarget,
            Long feeRateSatsPerVbyte,
            String changeAddress);

    /**
     * Immutable PSBT construction output.
     *
     * @param psbt base64 or provider-native unsigned PSBT payload
     * @param feeSats network fee encoded in the PSBT, expressed in satoshis
     */
    public record FundedPsbt(String psbt, long feeSats) {
    }
}
