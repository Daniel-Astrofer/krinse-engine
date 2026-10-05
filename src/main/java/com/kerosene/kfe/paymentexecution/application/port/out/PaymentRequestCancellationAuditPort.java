package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentRequestCancellationSnapshot;

/** Appends the request cancellation audit in the same financial transaction as its state change. */
public interface PaymentRequestCancellationAuditPort {

    /** Appends the locked request's prior state in the same transaction as cancellation. */
    /** @param previous immutable request snapshot read under the cancellation lock */
    void recordCancelled(PaymentRequestCancellationSnapshot previous);
}
