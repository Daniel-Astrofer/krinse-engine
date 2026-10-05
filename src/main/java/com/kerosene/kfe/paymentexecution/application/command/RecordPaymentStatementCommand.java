package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

import java.util.UUID;

/**
 * Participant statement context, independent from transport credentials and persistence.
 * @param userId participant whose statement is being updated
 * @param executionId related payment execution
 * @param walletId participant wallet to which the row is attributed, if available
 * @param memo statement memo, if permitted by the caller
 * @param cancelled whether the entry represents cancellation
 */
public record RecordPaymentStatementCommand(
        long userId,
        PaymentExecutionId executionId,
        UUID walletId,
        String memo,
        boolean cancelled) {

    /** Requires a participant and execution identity before statement projection. */
    public RecordPaymentStatementCommand {
        if (userId <= 0 || executionId == null) {
            throw new IllegalArgumentException("user id and payment execution id are required");
        }
    }
}
