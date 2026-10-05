package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.result.PaymentCancellationHints;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

/** Returns preliminary cancellation affordances without authorizing the later cancellation action. */
public interface PaymentCancellationHintsUseCase {
    /** @param userId authenticated account @param executionId visible payment identity @return deny-by-default cancellation target hints */
    PaymentCancellationHints hintsFor(long userId, PaymentExecutionId executionId);
}
