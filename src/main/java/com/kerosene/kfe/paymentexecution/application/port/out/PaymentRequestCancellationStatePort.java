package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentRequestCancellationSnapshot;

import java.util.UUID;

/** Requires the caller's financial transaction and previously acquired request cancellation lock. */
public interface PaymentRequestCancellationStatePort {

    /** Loads the locked request snapshot visible to the specified owner. */
    PaymentRequestCancellationSnapshot load(long userId, UUID id);

    /** Rejects ineligible or changed state before writing the cancellation. */
    void markCancelled(PaymentRequestCancellationSnapshot previous);
}
