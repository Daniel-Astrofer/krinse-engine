package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

/**
 * Authenticated intent to cancel a participant-visible payment.
 * @param userId authenticated account acting on the payment
 * @param paymentExecutionId execution selected for cancellation
 */
public record CancelPaymentCommand(long userId, PaymentExecutionId paymentExecutionId) {

    /** Requires an authenticated account and an execution identity. */
    public CancelPaymentCommand {
        if (userId <= 0 || paymentExecutionId == null) {
            throw new IllegalArgumentException("user id and payment execution id are required");
        }
    }
}
