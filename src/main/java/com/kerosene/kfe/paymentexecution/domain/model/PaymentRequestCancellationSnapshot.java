package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable request state read while the caller holds the request cancellation lock.
 * @param id persisted payment request identity
 * @param userId account that owns the request
 * @param walletId request recipient wallet
 * @param publicId client-visible request identifier
 * @param status request lifecycle state
 * @param rail payment rail used by the request
 * @param paymentHash Lightning payment hash, if present
 * @param providerReference provider-side invoice reference, if present
 * @param paymentRequest Lightning invoice/request text, if present
 * @param paidTransactionId execution already linked as payment, if any
 */
public record PaymentRequestCancellationSnapshot(
        UUID id,
        long userId,
        UUID walletId,
        String publicId,
        PaymentRequestCancellationStatus status,
        PaymentRail rail,
        String paymentHash,
        String providerReference,
        String paymentRequest,
        UUID paidTransactionId) {

    /** Requires identity, owner, request state, and rail for safe cancellation decisions. */
    public PaymentRequestCancellationSnapshot {
        Objects.requireNonNull(id, "payment request id is required");
        Objects.requireNonNull(status, "payment request status is required");
        Objects.requireNonNull(rail, "payment rail is required");
        if (userId <= 0L) {
            throw new IllegalArgumentException("user id must be positive");
        }
    }

    /** Returns whether the request lifecycle permits cancellation. */
    /** @return true when the request status is cancellable */
    public boolean cancellable() {
        return status.cancellable();
    }

    /** Checks whether a Lightning invoice provider cancellation is required. */
    /** @return true when the request uses Lightning and carries provider invoice identifiers */
    public boolean invoiceCancellationRequired() {
        return rail == PaymentRail.LIGHTNING
                && (hasText(paymentHash) || hasText(providerReference) || hasText(paymentRequest));
    }

    /** Returns request identity and lifecycle metadata without exposing invoice references. */
    @Override
    public String toString() {
        return "PaymentRequestCancellationSnapshot[id=" + id
                + ", userId=" + userId + ", status=" + status + ", rail=" + rail + "]";
    }

    /** Reports whether optional invoice/provider text is present. */
    /** @param value candidate value @return true for non-null, non-blank content */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
