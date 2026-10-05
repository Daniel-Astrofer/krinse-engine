package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import java.util.List;

/** Reserves cancellation against dispatch, joining the caller's financial transaction. */
public interface PaymentCancellationFencePort {
    /** Locks out competing dispatch/state observers for every execution in the cancellation batch. */
    /** @param executionIds complete related execution set discovered under the request lock */
    void fence(List<PaymentExecutionId> executionIds);
}
