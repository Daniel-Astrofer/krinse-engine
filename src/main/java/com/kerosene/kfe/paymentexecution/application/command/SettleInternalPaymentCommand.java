package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

/**
 * Issued by the authorized submit flow after gate approval and reservation, in its transaction.
 * @param userId authenticated sender account
 * @param executionId locked internal payment execution to settle
 */
public record SettleInternalPaymentCommand(long userId, PaymentExecutionId executionId) {
    /** Requires sender identity and execution identity. */
    public SettleInternalPaymentCommand {
        if (userId <= 0L || executionId == null) {
            throw new IllegalArgumentException("user id and execution id are required");
        }
    }
}
