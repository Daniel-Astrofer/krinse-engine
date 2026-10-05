package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.RequestFingerprint;
import java.util.Objects;

/**
 * Step of the authorized submission, not an authorization ticket or standalone reservation.
 * @param userId authenticated account that owns the reservation
 * @param idempotencyKey client key being reserved
 * @param fingerprint canonical request fingerprint bound to that key
 */
public record ReservePaymentIdempotencyCommand(long userId, IdempotencyKey idempotencyKey, RequestFingerprint fingerprint) {
    /** Requires valid user, idempotency, and canonical request identities. */
    public ReservePaymentIdempotencyCommand {
        if (userId <= 0L) { throw new IllegalArgumentException("authenticated user id must be positive"); }
        Objects.requireNonNull(idempotencyKey, "idempotency key is required");
        Objects.requireNonNull(fingerprint, "request fingerprint is required");
    }
    /** Returns account identity while redacting the idempotency key and request fingerprint. */
    @Override public String toString() { return "ReservePaymentIdempotencyCommand[userId=" + userId + ", idempotency=REDACTED]"; }
}
