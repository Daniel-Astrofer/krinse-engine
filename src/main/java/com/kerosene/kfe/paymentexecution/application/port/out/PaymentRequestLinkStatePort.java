package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRequestLinkExecution;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRequestLinkSnapshot;
import java.util.UUID;

/** Provides transaction-scoped locks and state changes for public payment-request links. */
public interface PaymentRequestLinkStatePort {
    /** Locks a link addressed by its public identifier and returns its current snapshot. */
    PaymentRequestLinkSnapshot lockByPublicId(String publicId);
    /** Locks the request identified for its recipient, enforcing that recipient scope. */
    PaymentRequestLinkSnapshot lockById(long recipientUserId, UUID requestId);
    /** Locks the payer-owned execution before associating it with a request link. */
    PaymentRequestLinkExecution lockExecution(long payerUserId, PaymentExecutionId executionId);
    /** Marks the locked link paid by the given execution, checking the prior snapshot. */
    void markPaid(PaymentRequestLinkSnapshot previous, PaymentExecutionId executionId);
}
