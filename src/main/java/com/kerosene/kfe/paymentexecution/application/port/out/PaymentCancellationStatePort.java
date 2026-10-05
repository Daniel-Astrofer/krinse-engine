package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentCancellationSnapshot;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

/** Reads and updates a payment in the same transaction that already holds its cancellation fence. */
public interface PaymentCancellationStatePort {
    /** Reloads the execution under the caller's previously acquired cancellation fence. */
    /** @param executionId fenced payment identity @return current authoritative cancellation snapshot */
    PaymentCancellationSnapshot load(PaymentExecutionId executionId);

    /** Persist FAILED / USER_CANCELLED only if the locked payment still matches the supplied state. */
    void markCancelled(PaymentCancellationSnapshot previous, String message);
}
