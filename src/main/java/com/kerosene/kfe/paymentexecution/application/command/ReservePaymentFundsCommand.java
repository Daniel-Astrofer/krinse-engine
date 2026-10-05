package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

/**
 * Internal submit step after authorization and gate approval, never an independent payment endpoint.
 * @param userId authenticated account that owns the reserved payment
 * @param executionId payment execution whose prepared funds are to be reserved
 */
public record ReservePaymentFundsCommand(long userId, PaymentExecutionId executionId) {
    /** Requires an authenticated account and execution identity. */
    public ReservePaymentFundsCommand {
        if (userId <= 0L || executionId == null) {
            throw new IllegalArgumentException("user id and execution id are required");
        }
    }
}
