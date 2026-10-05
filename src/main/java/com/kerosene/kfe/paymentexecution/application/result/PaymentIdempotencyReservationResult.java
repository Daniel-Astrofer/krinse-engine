package com.kerosene.kfe.paymentexecution.application.result;

/**
 * Result of attempting to reserve a key; pending/conflict are errors, and only a new reservation
 * may start financial effects.
 * @param reserved true when this request inserted the winning reservation
 * @param existingPayment current result when this request is a matching completed replay
 */
public record PaymentIdempotencyReservationResult(boolean reserved, PaymentExecutionResult existingPayment) {
    /** Enforces an exclusive result: exactly one of newly reserved or replayed payment is present. */
    public PaymentIdempotencyReservationResult {
        if (reserved == (existingPayment != null)) {
            throw new IllegalArgumentException("Expected either a new reservation or an existing payment.");
        }
    }
    /** Creates the result for the caller that won the unique reservation insert. */
    /** @return new-reservation result permitted to continue payment effects */
    public static PaymentIdempotencyReservationResult reservedNew() { return new PaymentIdempotencyReservationResult(true, null); }
    /** Creates the result for a matching completed reservation owned by an earlier request. */
    /** @param payment existing payment projection @return replay result that must not repeat financial effects */
    public static PaymentIdempotencyReservationResult replay(PaymentExecutionResult payment) {
        return new PaymentIdempotencyReservationResult(false, payment);
    }
    /** Returns reservation outcome while redacting the existing payment projection. */
    @Override public String toString() {
        return "PaymentIdempotencyReservationResult[reserved=" + reserved + ", existingPayment=REDACTED]";
    }
}
