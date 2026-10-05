package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.application.result.PreparedPaymentRequestLink;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

/**
 * Completes an accepted request link after the payment execution has settled.
 * @param userId authenticated payer account
 * @param link request acceptance snapshot created during the same authorized submit
 * @param executionId settled execution linked as payment
 */
public record CompletePaymentRequestLinkCommand(long userId, PreparedPaymentRequestLink link, PaymentExecutionId executionId) {
    /** Requires an authenticated payer matching the accepted request and a settled execution identity. */
    public CompletePaymentRequestLinkCommand {
        if (userId <= 0L || link == null || executionId == null || userId != link.payerUserId()) {
            throw new IllegalArgumentException("authenticated payer, accepted request and execution are required");
        }
    }
}
