package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Authoritative route, amounts, and identity reread under the execution lock before routing.
 * @param executionId persisted execution identity
 * @param userId owning account identifier
 * @param status lifecycle state observed under lock
 * @param rail payment rail selected for the execution
 * @param direction payment direction selected for the execution
 * @param idempotencyKey key bound to the authorized execution
 * @param sourceWalletId source wallet for flows that debit a wallet
 * @param destinationWalletId destination wallet for inbound/internal flows
 * @param grossAmountSats authorized gross payment amount
 * @param receiverAmountSats amount delivered to the recipient
 * @param networkFeeSats reserved network fee
 * @param totalDebitSats complete source debit
 * @param externalReference canonical external destination/reference
 * @param memo canonical payment memo
 * @param proposalHash quorum-approved proposal digest
 */
public record PaymentRoutingSnapshot(PaymentExecutionId executionId, long userId, ExecutionStatus status,
        PaymentRail rail, PaymentDirection direction, IdempotencyKey idempotencyKey,
        UUID sourceWalletId, UUID destinationWalletId, long grossAmountSats, long receiverAmountSats,
        long networkFeeSats, long totalDebitSats, String externalReference, String memo, String proposalHash) {
    /** Enforces identity, routing, and amount invariants at the persistence boundary. */
    public PaymentRoutingSnapshot {
        Objects.requireNonNull(executionId, "execution id is required");
        Objects.requireNonNull(status, "execution status is required");
        Objects.requireNonNull(rail, "rail is required");
        Objects.requireNonNull(direction, "direction is required");
        Objects.requireNonNull(idempotencyKey, "idempotency key is required");
        if (userId <= 0L || grossAmountSats <= 0L || receiverAmountSats <= 0L || networkFeeSats < 0L || totalDebitSats < 0L) {
            throw new IllegalArgumentException("routing identity and financial amounts must be valid");
        }
    }

    /** Confirms that the locked snapshot belongs to this caller and is ready for the requested route. */
    /** @param authenticatedUserId caller account @param requestedId requested execution @throws IllegalStateException when state, identity, amounts, or proposal evidence are not routing-ready */
    public void requireReadyFor(long authenticatedUserId, PaymentExecutionId requestedId) {
        if (userId != authenticatedUserId || !executionId.equals(requestedId) || status != ExecutionStatus.LOCKED
                || (rail == PaymentRail.INTERNAL) != (direction == PaymentDirection.INTERNAL)
                || (direction != PaymentDirection.INBOUND && (sourceWalletId == null || totalDebitSats <= 0L))
                || (direction != PaymentDirection.OUTBOUND && destinationWalletId == null)
                || proposalHash == null || proposalHash.isBlank()) {
            throw new IllegalStateException("Payment is not eligible for routing.");
        }
    }

    /** Selects the wallet whose statement should represent this route. */
    /** @return destination wallet for inbound transfers; source wallet otherwise */
    public UUID statementWalletId() {
        return direction == PaymentDirection.INBOUND ? destinationWalletId : sourceWalletId;
    }

    /** Returns route metadata while redacting destination references and memo content. */
    @Override
    public String toString() {
        return "PaymentRoutingSnapshot[executionId=" + executionId + ", userId=" + userId + ", status=" + status
                + ", rail=" + rail + ", direction=" + direction + ", references=REDACTED]";
    }
}
