package com.kerosene.kfe.paymentexecution.application.query;

import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import java.util.Objects;

/** Read-only recovery of a submission whose HTTP response may have been lost. */
public record GetSubmittedPaymentQuery(long userId, IdempotencyKey idempotencyKey) {
    public GetSubmittedPaymentQuery {
        if (userId <= 0L) {
            throw new IllegalArgumentException("authenticated user id must be positive");
        }
        Objects.requireNonNull(idempotencyKey, "submission key is required");
    }

    @Override
    public String toString() {
        return "GetSubmittedPaymentQuery[userId=" + userId + ", idempotency=REDACTED]";
    }
}
