package com.kerosene.kfe.paymentexecution.application.query;

import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.RequestFingerprint;
import java.util.Objects;

public record GetIdempotentPaymentQuery(long userId, IdempotencyKey idempotencyKey, RequestFingerprint fingerprint) {
    public GetIdempotentPaymentQuery {
        if (userId <= 0L) { throw new IllegalArgumentException("authenticated user id must be positive"); }
        Objects.requireNonNull(idempotencyKey, "idempotency key is required");
        Objects.requireNonNull(fingerprint, "request fingerprint is required");
    }
    @Override public String toString() { return "GetIdempotentPaymentQuery[userId=" + userId + ", idempotency=REDACTED]"; }
}
