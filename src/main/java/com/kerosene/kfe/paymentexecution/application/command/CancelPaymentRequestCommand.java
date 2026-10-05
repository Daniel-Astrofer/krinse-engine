package com.kerosene.kfe.paymentexecution.application.command;

import java.util.UUID;

/**
 * Authenticated intent; request metadata never supplies the acting user.
 * @param userId authenticated account requesting cancellation
 * @param paymentRequestId request aggregate to cancel
 */
public record CancelPaymentRequestCommand(long userId, UUID paymentRequestId) {
    /** Requires an authenticated account and payment request identity. */
    public CancelPaymentRequestCommand {
        if (userId <= 0 || paymentRequestId == null) {
            throw new IllegalArgumentException("user id and payment request id are required");
        }
    }
}
