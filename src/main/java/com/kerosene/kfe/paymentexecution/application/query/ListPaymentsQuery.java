package com.kerosene.kfe.paymentexecution.application.query;

import java.time.Instant;

/** Authenticated paged query, optionally restricted to updates after an instant. */
public record ListPaymentsQuery(long userId, int page, int size, Instant since) {

    public ListPaymentsQuery {
        if (userId <= 0) {
            throw new IllegalArgumentException("user id is required");
        }
    }
}
