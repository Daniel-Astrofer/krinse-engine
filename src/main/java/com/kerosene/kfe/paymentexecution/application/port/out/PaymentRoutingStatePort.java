package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRoutingSnapshot;

/** Loads and persists routing decisions under the payment execution's transaction lock. */
public interface PaymentRoutingStatePort {
    /** Locks and reads the user's execution routing state in the caller's transaction. */
    PaymentRoutingSnapshot lockAndLoad(long userId, PaymentExecutionId executionId);
    /** Flushes the changed routing state for the same owner and execution. */
    void flush(long userId, PaymentExecutionId executionId);
}
