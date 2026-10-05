package com.kerosene.kfe.paymentexecution.application.result;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentRequestLinkSnapshot;

/**
 * Internal continuity snapshot, not a transport capability or a substitute for authorization.
 * @param payerUserId account that accepted the request
 * @param request locked recipient-owned request snapshot accepted by this payment
 * @param amountSats exact amount validated against the request
 */
public record PreparedPaymentRequestLink(long payerUserId, PaymentRequestLinkSnapshot request, long amountSats) {
    /** Requires an authenticated payer, request state, and a positive accepted amount. */
    public PreparedPaymentRequestLink {
        if (payerUserId <= 0L || request == null || amountSats <= 0L) {
            throw new IllegalArgumentException("payer, request and positive amount are required");
        }
    }
}
