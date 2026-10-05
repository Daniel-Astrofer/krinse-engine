package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Authoritative financial inputs read while the caller holds the execution lock.
 * @param executionId persisted execution identity
 * @param userId sender account identifier
 * @param status lifecycle state observed under lock
 * @param rail selected payment rail
 * @param direction transfer direction
 * @param sourceWalletId sender wallet identifier
 * @param destinationWalletId recipient wallet identifier
 * @param recipientUserId recipient account identifier
 * @param totalDebitSats sender debit including fees in integer satoshis
 * @param receiverAmountSats amount credited to recipient in integer satoshis
 */
public record InternalPaymentSettlementSnapshot(
        PaymentExecutionId executionId, long userId, ExecutionStatus status,
        PaymentRail rail, PaymentDirection direction, UUID sourceWalletId, UUID destinationWalletId,
        long recipientUserId, long totalDebitSats, long receiverAmountSats) {

    /** Requires both participants, wallets, and consistent positive settlement amounts. */
    public InternalPaymentSettlementSnapshot {
        Objects.requireNonNull(executionId, "execution id is required");
        Objects.requireNonNull(status, "execution status is required");
        Objects.requireNonNull(rail, "rail is required");
        Objects.requireNonNull(direction, "direction is required");
        Objects.requireNonNull(sourceWalletId, "source wallet is required");
        Objects.requireNonNull(destinationWalletId, "destination wallet is required");
        if (userId <= 0L || recipientUserId <= 0L) {
            throw new IllegalArgumentException("settlement participant ids must be positive");
        }
        if (receiverAmountSats <= 0L || totalDebitSats < receiverAmountSats) {
            throw new IllegalArgumentException("internal settlement amounts are inconsistent");
        }
    }

    /** Confirms ownership, INTERNAL routing, LOCKED state, and distinct source/destination wallets. */
    /** @param authenticatedUserId sender account making the request @param requestedId execution being settled @throws IllegalStateException when the snapshot is not eligible for internal settlement */
    public void requireReadyFor(long authenticatedUserId, PaymentExecutionId requestedId) {
        if (userId != authenticatedUserId || !executionId.equals(requestedId)
                || rail != PaymentRail.INTERNAL || direction != PaymentDirection.INTERNAL
                || status != ExecutionStatus.LOCKED || sourceWalletId.equals(destinationWalletId)) {
            throw new IllegalStateException("Internal payment is not eligible for settlement.");
        }
    }

    /** Indicates whether settlement must notify a separate recipient account. */
    /** @return true when sender and recipient account identifiers differ */
    public boolean hasAnotherRecipient() {
        return userId != recipientUserId;
    }
}
