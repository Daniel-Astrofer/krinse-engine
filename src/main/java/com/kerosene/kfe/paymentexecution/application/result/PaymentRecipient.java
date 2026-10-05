package com.kerosene.kfe.paymentexecution.application.result;

/**
 * Minimal directory projection for resolving an internal payment recipient.
 * @param userId recipient account identifier
 * @param active whether the account may receive payments
 */
public record PaymentRecipient(long userId, boolean active) {
    /** Requires a positive recipient account identifier. */
    public PaymentRecipient {
        if (userId <= 0L) { throw new IllegalArgumentException("recipient id must be positive"); }
    }
}
