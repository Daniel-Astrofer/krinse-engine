package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.UUID;

/**
 * Existing canonical proposal wire representation used to derive the quorum digest.
 * Do not normalize original references or log the canonical preimage.
 * @param executionId payment execution identity bound into the proposal
 * @param userId account submitting the payment
 * @param rail selected payment rail
 * @param direction payment direction
 * @param sourceWalletId authorized source wallet, if any
 * @param destinationWalletId resolved destination wallet, if any
 * @param grossAmountSats gross amount in integer satoshis
 * @param receiverAmountSats recipient amount in integer satoshis
 * @param networkFeeSats network fee in integer satoshis
 * @param keroseneFeeSats platform fee in integer satoshis
 * @param totalDebitSats complete source debit in integer satoshis
 * @param externalReference exact authorized external reference
 * @param paymentRequestPublicId exact public payment request identifier, if present
 */
public record PaymentProposal(PaymentExecutionId executionId, long userId, PaymentRail rail, PaymentDirection direction,
        UUID sourceWalletId, UUID destinationWalletId, long grossAmountSats, long receiverAmountSats,
        long networkFeeSats, long keroseneFeeSats, long totalDebitSats,
        String externalReference, String paymentRequestPublicId) {
    /**
     * Serializes the established wire fields in stable delimiter-separated order for hashing.
     * References are preserved exactly because normalization would change the signed proposal.
     * @return canonical proposal preimage; callers must not log or expose it
     */
    public String canonicalContent() {
        return String.join("|", "KFE_TX_PROPOSAL", executionId.value().toString(), String.valueOf(userId),
                rail.name(), direction.name(), String.valueOf(sourceWalletId), String.valueOf(destinationWalletId),
                String.valueOf(grossAmountSats), String.valueOf(receiverAmountSats), String.valueOf(networkFeeSats),
                String.valueOf(keroseneFeeSats), String.valueOf(totalDebitSats),
                externalReference != null ? externalReference : "", paymentRequestPublicId != null ? paymentRequestPublicId : "");
    }
    /** Returns non-sensitive identifiers while redacting the proposal's destination references. */
    @Override public String toString() {
        return "PaymentProposal[executionId=" + executionId + ", userId=" + userId
                + ", rail=" + rail + ", direction=" + direction + ", references=REDACTED]";
    }
}
