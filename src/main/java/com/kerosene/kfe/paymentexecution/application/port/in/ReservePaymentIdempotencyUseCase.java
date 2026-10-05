package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.ReservePaymentIdempotencyCommand;
import com.kerosene.kfe.paymentexecution.application.result.PaymentIdempotencyReservationResult;

/** Atomically reserves a key or returns a completed matching replay result. */
public interface ReservePaymentIdempotencyUseCase {
    /** @param command account, idempotency key, and semantic request fingerprint @return reservation winner or replay result */
    PaymentIdempotencyReservationResult reserve(ReservePaymentIdempotencyCommand command);
}
