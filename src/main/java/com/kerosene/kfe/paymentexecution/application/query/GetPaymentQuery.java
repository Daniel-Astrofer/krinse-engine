package com.kerosene.kfe.paymentexecution.application.query;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

/** Authenticated query for one participant-visible payment execution. */
public record GetPaymentQuery(long userId, PaymentExecutionId paymentExecutionId) {

    public GetPaymentQuery {
        if (userId <= 0 || paymentExecutionId == null) {
            throw new IllegalArgumentException("user id and payment execution id are required");
        }
    }
}
