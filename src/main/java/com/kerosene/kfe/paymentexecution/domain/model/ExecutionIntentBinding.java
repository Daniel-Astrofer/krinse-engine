package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.UUID;

/**
 * Immutable values bound to the authorized execution and its durable outbound message.
 * @param transactionId persisted execution identity
 * @param userId owning account identifier
 * @param idempotencyKey idempotency key bound to the request
 * @param rail payment rail selected during authorization
 * @param direction payment direction selected during authorization
 * @param sourceWalletId authorized source wallet, when applicable
 * @param destinationWalletId resolved destination wallet, when applicable
 * @param amountSats authorized principal amount in integer satoshis
 * @param networkFeeSats authorized network fee reserve in integer satoshis
 * @param totalDebitSats complete source debit in integer satoshis
 * @param externalReference normalized external destination/reference
 * @param memo canonical payment memo
 * @param quorumProposalHash proposal digest whose quorum evidence gates settlement
 */
public record ExecutionIntentBinding(
        UUID transactionId,
        long userId,
        String idempotencyKey,
        PaymentRail rail,
        PaymentDirection direction,
        UUID sourceWalletId,
        UUID destinationWalletId,
        long amountSats,
        long networkFeeSats,
        long totalDebitSats,
        String externalReference,
        String memo,
        String quorumProposalHash
) {
    /** Returns identifiers and route metadata while redacting references and memo content. */
    @Override
    public String toString() {
        return "ExecutionIntentBinding[transactionId=" + transactionId
                + ", rail=" + rail + ", direction=" + direction + ", references=REDACTED]";
    }
}
