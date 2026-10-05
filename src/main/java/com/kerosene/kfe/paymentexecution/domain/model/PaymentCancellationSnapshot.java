package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable financial state reread after the cancellation fence has acquired its locks.
 * @param executionId persisted execution identity
 * @param userId owning account identifier
 * @param status lifecycle state observed under the cancellation fence
 * @param rail payment rail associated with the execution
 * @param direction payment direction
 * @param sourceWalletId source wallet whose reserve may need release
 * @param destinationWalletId destination wallet associated with the execution
 * @param totalDebitSats reserved total debit in integer satoshis
 * @param blockchainTransactionId known external chain transaction, if already dispatched
 */
public record PaymentCancellationSnapshot(
        PaymentExecutionId executionId,
        long userId,
        ExecutionStatus status,
        PaymentRail rail,
        PaymentDirection direction,
        UUID sourceWalletId,
        UUID destinationWalletId,
        long totalDebitSats,
        String blockchainTransactionId) {

    /** Requires complete identity and nonnegative debit information for cancellation decisions. */
    public PaymentCancellationSnapshot {
        Objects.requireNonNull(executionId, "execution id is required");
        Objects.requireNonNull(status, "execution status is required");
        Objects.requireNonNull(rail, "payment rail is required");
        Objects.requireNonNull(direction, "payment direction is required");
        if (userId <= 0L) {
            throw new IllegalArgumentException("user id must be positive");
        }
        if (totalDebitSats < 0L) {
            throw new IllegalArgumentException("total debit cannot be negative");
        }
    }

    /** Delegates lifecycle incompleteness to the authoritative execution state model. */
    /** @return true while cancellation/recovery closure may still be required */
    public boolean incomplete() {
        return PaymentExecution.reconstitute(executionId, status).isIncomplete();
    }

    /** Checks status and dispatch evidence for preliminary cancellation eligibility. */
    /** @return true when the current lifecycle permits cancellation */
    public boolean cancellable() {
        return PaymentExecution.reconstitute(executionId, status).canBeCancelled(blockchainTransactionId);
    }

    /** Indicates whether the ledger reserve must be released when cancellation succeeds. */
    /** @return true for a reserved execution with a source wallet and positive debit */
    public boolean requiresReserveRelease() {
        return (status == ExecutionStatus.LOCKED || status == ExecutionStatus.EXECUTING)
                && sourceWalletId != null && totalDebitSats > 0L;
    }

    /** Indicates whether outbound Lightning capacity is associated with this execution. */
    /** @return true for Lightning outbound flow */
    public boolean requiresLiquidityRelease() {
        return rail == PaymentRail.LIGHTNING && direction == PaymentDirection.OUTBOUND;
    }

    /** Selects the account wallet represented in a cancellation statement. */
    /** @return source wallet when present, otherwise the destination wallet */
    public UUID statementWalletId() {
        return sourceWalletId != null ? sourceWalletId : destinationWalletId;
    }
}
