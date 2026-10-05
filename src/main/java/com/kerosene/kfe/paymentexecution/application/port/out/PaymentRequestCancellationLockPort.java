package com.kerosene.kfe.paymentexecution.application.port.out;

import java.util.UUID;

/** Serializes cancellation with payment-request observations and validates ownership. */
public interface PaymentRequestCancellationLockPort {
    /** Locks and authorizes the request row for this user's cancellation transaction. */
    void lock(long userId, UUID paymentRequestId);
}
