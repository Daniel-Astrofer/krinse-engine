package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyReservation;

import java.util.Optional;

/** Durable client-scoped idempotency boundary. */
public interface IdempotencyReservationStore {

    /** Reads one account-scoped key reservation without mutating it. */
    /** @param userId reservation owner @param key client-provided idempotency key @return reservation when present */
    Optional<IdempotencyReservation> find(long userId, IdempotencyKey key);

    /** Atomic insert only in the caller's READ_COMMITTED financial transaction. False never overwrites an existing row. */
    boolean reserve(long userId, IdempotencyReservation reservation);

    /** Joins the financial transaction. Returns false for the same already-persisted binding and status. */
    boolean complete(long userId, IdempotencyReservation reservation, ExecutionStatus status);
}
