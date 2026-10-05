package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.ReservePaymentIdempotencyCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.IdempotencyReservationStore;
import com.kerosene.kfe.paymentexecution.application.query.GetIdempotentPaymentQuery;
import com.kerosene.kfe.paymentexecution.application.result.PaymentIdempotencyReservationResult;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyReservation;

import java.util.Objects;

/** Insert-or-replay within the authorized submission. Only the insert winner may begin financial effects. */
public final class ReservePaymentIdempotencyService {
    /** Atomic insert-or-conflict operation for account-scoped idempotency reservations. */
    private final IdempotencyReservationStore reservations;
    /** Reads a committed winning payment after a uniqueness conflict. */
    private final GetIdempotentPaymentService replay;

    /** Creates idempotency reservation orchestration with storage and replay lookup. */
    /** @param reservations unique reservation persistence port @param replay idempotent result lookup service */
    public ReservePaymentIdempotencyService(IdempotencyReservationStore reservations, GetIdempotentPaymentService replay) {
        this.reservations = reservations;
        this.replay = replay;
    }

    /**
     * Reserves a fresh request or returns the committed matching result after a uniqueness conflict.
     * Only the insert winner may continue into payment financial effects.
     * @param command account, key, and canonical request fingerprint
     * @return newly reserved result or replay of the existing payment
     */
    public PaymentIdempotencyReservationResult reserve(ReservePaymentIdempotencyCommand command) {
        Objects.requireNonNull(command, "idempotency reservation command is required");
        var reservation = IdempotencyReservation.pending(command.idempotencyKey(), command.fingerprint());
        if (reservations.reserve(command.userId(), reservation)) {
            return PaymentIdempotencyReservationResult.reservedNew();
        }
        // The conflict was not an SQL error. A fresh statement can see the committed winner under READ_COMMITTED.
        var existing = Objects.requireNonNull(replay.find(new GetIdempotentPaymentQuery(
                command.userId(), command.idempotencyKey(), command.fingerprint())), "idempotent replay result is required")
                .orElseThrow(() -> new IllegalStateException("Idempotency conflict detected, but no record found."));
        return PaymentIdempotencyReservationResult.replay(existing);
    }
}
