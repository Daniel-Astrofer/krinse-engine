package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import java.util.Optional;

/** Owner- and idempotency-scoped read projection used only after a reservation match. */
public interface PaymentIdempotencyQueryPort {
    /** Current consumer projection, scoped to execution owner AND original idempotency key; no participant visibility or write lock. */
    Optional<PaymentExecutionResult> findOwnedByIdAndKey(long userId, PaymentExecutionId executionId, IdempotencyKey key);
}
