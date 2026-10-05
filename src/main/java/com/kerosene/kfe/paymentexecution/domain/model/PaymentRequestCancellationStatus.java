package com.kerosene.kfe.paymentexecution.domain.model;

/** Request-state projection used by Payment Execution, not the full payment-request aggregate. */
public enum PaymentRequestCancellationStatus {
    /** Request is available to be paid or cancelled. */
    OPEN,
    /** A payment execution has been linked as the successful payment. */
    PAID,
    /** Request is beyond its expiry but may still be cancelled/closed. */
    EXPIRED,
    /** Request is hidden and no longer available for public acceptance. */
    HIDDEN,
    /** Request was explicitly cancelled. */
    CANCELLED,
    /** Request reached a failed terminal state. */
    FAILED;

    /** Reports whether cancellation may transition this projected request state. */
    /** @return true for OPEN or EXPIRED requests */
    public boolean cancellable() {
        return this == OPEN || this == EXPIRED;
    }
}
