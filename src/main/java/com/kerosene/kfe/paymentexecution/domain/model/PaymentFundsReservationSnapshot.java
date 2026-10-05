package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Financial inputs reloaded under the execution lock, not supplied by a transport caller.
 * @param executionId persisted execution identity
 * @param userId owning account identifier
 * @param status lifecycle state at reservation time
 * @param rail selected payment rail
 * @param direction payment direction
 * @param sourceWalletId source wallet used for a debit reservation, if required
 * @param totalDebitSats total debit to reserve in integer satoshis
 * @param proposalHash quorum-approved proposal digest
 * @param quorumAckCount accepted quorum acknowledgement count
 */
public record PaymentFundsReservationSnapshot(
        PaymentExecutionId executionId, long userId, ExecutionStatus status,
        PaymentRail rail, PaymentDirection direction, UUID sourceWalletId,
        long totalDebitSats, String proposalHash, int quorumAckCount) {

    /** Validates complete identity and nonnegative amount/quorum values from persisted state. */
    public PaymentFundsReservationSnapshot {
        Objects.requireNonNull(executionId, "execution id is required");
        Objects.requireNonNull(status, "execution status is required");
        Objects.requireNonNull(rail, "rail is required");
        Objects.requireNonNull(direction, "direction is required");
        if (userId <= 0L || totalDebitSats < 0L || quorumAckCount < 0) {
            throw new IllegalArgumentException("reservation identity, amount and quorum count must be valid");
        }
    }

    /** Confirms the snapshot is owned by the caller and has quorum-ready reservation state. */
    /** @param authenticatedUserId caller account identifier @param requestedId requested execution identifier @throws IllegalStateException when the state is not eligible for reservation */
    public void requireReadyFor(long authenticatedUserId, PaymentExecutionId requestedId) {
        if (userId != authenticatedUserId || !executionId.equals(requestedId)
                || status != ExecutionStatus.QUORUM_SYNC
                || (rail == PaymentRail.INTERNAL) != (direction == PaymentDirection.INTERNAL)
                || proposalHash == null || proposalHash.isBlank()
                || (requiresSourceReserve() && (sourceWalletId == null || totalDebitSats <= 0L))) {
            throw new IllegalStateException("Payment is not eligible for funds reservation.");
        }
    }

    /** Determines whether this flow must reserve source-wallet funds. */
    /** @return true for outbound and internal transfers */
    public boolean requiresSourceReserve() {
        return direction == PaymentDirection.OUTBOUND || direction == PaymentDirection.INTERNAL;
    }

    /** Determines whether the flow must reserve Lightning outbound capacity. */
    /** @return true only for Lightning outbound transfers */
    public boolean requiresLightningLiquidity() {
        return rail == PaymentRail.LIGHTNING && direction == PaymentDirection.OUTBOUND;
    }

    /** Returns lifecycle and amount metadata while redacting the proposal digest. */
    @Override
    public String toString() {
        return "PaymentFundsReservationSnapshot[executionId=" + executionId + ", userId=" + userId
                + ", status=" + status + ", rail=" + rail + ", direction=" + direction
                + ", totalDebitSats=" + totalDebitSats + ", quorumAckCount=" + quorumAckCount
                + ", proposal=REDACTED]";
    }
}
