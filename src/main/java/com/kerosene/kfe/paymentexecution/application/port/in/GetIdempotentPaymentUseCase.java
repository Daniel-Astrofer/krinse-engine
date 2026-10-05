package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.query.GetIdempotentPaymentQuery;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import java.util.Optional;

/** Resolves a completed matching idempotency reservation without repeating financial effects. */
public interface GetIdempotentPaymentUseCase {
    /** @param query account, key, and canonical request fingerprint @return existing payment result, or empty when no reservation exists */
    Optional<PaymentExecutionResult> find(GetIdempotentPaymentQuery query);
}
