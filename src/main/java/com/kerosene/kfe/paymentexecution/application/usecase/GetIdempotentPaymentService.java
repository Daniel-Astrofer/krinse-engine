package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.port.out.IdempotencyReservationStore;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentIdempotencyQueryPort;
import com.kerosene.kfe.paymentexecution.application.query.GetIdempotentPaymentQuery;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;

import java.util.Objects;
import java.util.Optional;

/** Read-only replay decision. A replay uses the current owned execution and never repeats financial effects. */
public final class GetIdempotentPaymentService {
    /** Reads reservation state and request fingerprint for account-scoped replay decisions. */
    private final IdempotencyReservationStore reservations;
    /** Loads an owned execution result only after the reservation is complete and matching. */
    private final PaymentIdempotencyQueryPort payments;

    /** Creates read-only replay lookup with reservation and payment query ports. */
    /** @param reservations idempotency reservation lookup port @param payments account-owned execution result lookup port */
    public GetIdempotentPaymentService(IdempotencyReservationStore reservations, PaymentIdempotencyQueryPort payments) {
        this.reservations = reservations;
        this.payments = payments;
    }

    /**
     * Returns empty when no reservation exists; otherwise verifies the request fingerprint,
     * requires a completed execution, and returns its current owned projection.
     * @param query account, key, and request fingerprint used for replay binding
     * @return current payment result for a completed matching reservation
     * @throws IllegalStateException when the reservation conflicts, remains pending, or points to missing state
     */
    public Optional<PaymentExecutionResult> find(GetIdempotentPaymentQuery query) {
        Objects.requireNonNull(query, "idempotent payment query is required");
        var existing = Objects.requireNonNull(reservations.find(query.userId(), query.idempotencyKey()),
                "idempotency lookup result is required");
        if (existing.isEmpty()) { return Optional.empty(); }
        var reservation = existing.get();
        if (!query.idempotencyKey().equals(reservation.key())) {
            throw new IllegalStateException("Idempotency reservation key does not match the request.");
        }
        reservation.assertSameRequest(query.fingerprint());
        // Pending keeps the established retryable error; it is not permission to create another execution.
        var executionId = reservation.completedExecutionId();
        var payment = Objects.requireNonNull(payments.findOwnedByIdAndKey(query.userId(), executionId, query.idempotencyKey()),
                "idempotent execution lookup result is required")
                .orElseThrow(() -> new IllegalStateException("Idempotent transaction record is missing."));
        if (!executionId.value().equals(payment.id())) {
            throw new IllegalStateException("Idempotent transaction identity does not match the reservation.");
        }
        return Optional.of(payment);
    }
}
