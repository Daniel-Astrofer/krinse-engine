package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Persisted submission identity after routing, read under the execution lock.
 * @param executionId persisted payment execution identity
 * @param userId owning account identifier
 * @param idempotencyKey request key reserved for this execution
 * @param status persisted lifecycle status at completion
 * @param rail selected payment rail
 * @param direction payment direction
 * @param destinationWalletId resolved destination wallet, required for inbound/internal flows
 */
public record PaymentSubmissionCompletionSnapshot(PaymentExecutionId executionId, long userId,
        IdempotencyKey idempotencyKey, ExecutionStatus status, PaymentRail rail, PaymentDirection direction,
        UUID destinationWalletId) {
    /** Requires complete persisted identity, routing, and owner information. */
    public PaymentSubmissionCompletionSnapshot {
        Objects.requireNonNull(executionId, "execution id is required");
        Objects.requireNonNull(idempotencyKey, "idempotency key is required");
        Objects.requireNonNull(status, "execution status is required");
        Objects.requireNonNull(rail, "rail is required");
        Objects.requireNonNull(direction, "direction is required");
        if (userId <= 0L) { throw new IllegalArgumentException("execution owner must be positive"); }
    }
    /** Verifies ownership, request key, route category, state, and destination before completing. */
    /** @param authenticatedUserId caller account @param requestedId requested execution @param requestedKey reserved idempotency key @throws IllegalStateException when persisted completion state does not match the owning submission */
    public void requireReadyFor(long authenticatedUserId, PaymentExecutionId requestedId, IdempotencyKey requestedKey) {
        boolean internal = rail == PaymentRail.INTERNAL && direction == PaymentDirection.INTERNAL;
        boolean external = rail != PaymentRail.INTERNAL && direction != PaymentDirection.INTERNAL;
        if (userId != authenticatedUserId || !executionId.equals(requestedId) || !idempotencyKey.equals(requestedKey)
                || !(internal && status == ExecutionStatus.SETTLED || external && status == ExecutionStatus.EXECUTING)
                || direction != PaymentDirection.OUTBOUND && destinationWalletId == null) {
            throw new IllegalStateException("Payment is not eligible for submission completion.");
        }
    }
    /** Returns execution and routing metadata without exposing the idempotency key. */
    @Override public String toString() {
        return "PaymentSubmissionCompletionSnapshot[executionId=" + executionId + ", userId=" + userId
                + ", status=" + status + ", rail=" + rail + ", direction=" + direction + ", idempotency=REDACTED]";
    }
}
