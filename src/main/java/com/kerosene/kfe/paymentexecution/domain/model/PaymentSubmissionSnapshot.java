package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Intent identity read under the execution lock, before its first financial preparation.
 * @param executionId persisted intent identity
 * @param userId owning account identifier
 * @param status current lifecycle state
 * @param idempotencyKey request idempotency key
 * @param rail payment rail selected by the request
 * @param direction payment direction
 * @param sourceWalletId optional source wallet
 * @param destinationWalletId optional destination wallet
 * @param amountSats principal amount in integer satoshis
 * @param externalReference persisted canonical reference used to bind preparation
 */
public record PaymentSubmissionSnapshot(PaymentExecutionId executionId, long userId, ExecutionStatus status,
        IdempotencyKey idempotencyKey, PaymentRail rail, PaymentDirection direction, UUID sourceWalletId,
        UUID destinationWalletId, long amountSats, String externalReference) {
    /** Validates required persisted identity and a positive payment amount. */
    public PaymentSubmissionSnapshot {
        Objects.requireNonNull(executionId, "execution id is required");
        Objects.requireNonNull(status, "execution status is required");
        Objects.requireNonNull(idempotencyKey, "idempotency key is required");
        Objects.requireNonNull(rail, "rail is required");
        Objects.requireNonNull(direction, "direction is required");
        if (userId <= 0L || amountSats <= 0L) { throw new IllegalArgumentException("submission identity and amount must be valid"); }
    }
    /** Confirms ownership, intent lifecycle, rail/direction pair, and reference binding before preparation. */
    /** @param authenticatedUserId caller account identifier @param requestedId requested execution @param rawExternalReference input destination reference @param rawPublicId public payment request identifier @throws IllegalStateException for ownership/state/rail mismatch @throws IllegalArgumentException for reference mismatch */
    public void requireReadyFor(long authenticatedUserId, PaymentExecutionId requestedId,
            String rawExternalReference, String rawPublicId) {
        if (userId != authenticatedUserId || !executionId.equals(requestedId) || status != ExecutionStatus.INTENT
                || (rail == PaymentRail.INTERNAL) != (direction == PaymentDirection.INTERNAL)) {
            throw new IllegalStateException("Payment is not eligible for submission preparation.");
        }
        String publicId = clean(rawPublicId);
        String expectedReference = publicId != null ? publicId : clean(rawExternalReference);
        if (!Objects.equals(expectedReference, externalReference)
                || (publicId != null && rail != PaymentRail.INTERNAL)) {
            throw new IllegalArgumentException("Submission references do not match the persisted payment intent.");
        }
    }
    /** Trims optional reference data and maps blank values to null for comparison. */
    /** @param value raw optional reference @return trimmed value or null */
    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    /** Returns intent metadata without exposing its external reference. */
    @Override public String toString() {
        return "PaymentSubmissionSnapshot[executionId=" + executionId + ", userId=" + userId
                + ", status=" + status + ", rail=" + rail + ", direction=" + direction + ", references=REDACTED]";
    }
}
