package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.query.GetPaymentQuery;
import com.kerosene.kfe.paymentexecution.application.query.GetSubmittedPaymentQuery;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;

import java.util.Optional;

/** Reads one payment through participant-visible query rules. */
public interface GetPaymentUseCase {
    /** @param query authenticated participant and execution identity @return visible execution projection */
    PaymentExecutionResult get(GetPaymentQuery query);

    /** Recovers a submission by its owner-scoped idempotency key. */
    Optional<PaymentExecutionResult> findSubmitted(GetSubmittedPaymentQuery query);
}
