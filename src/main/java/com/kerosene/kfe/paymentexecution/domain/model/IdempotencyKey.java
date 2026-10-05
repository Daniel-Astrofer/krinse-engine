package com.kerosene.kfe.paymentexecution.domain.model;

/**
 * Client-scoped key that identifies one semantic payment submission.
 * @param value opaque client-provided key, retained exactly after validation
 */
public record IdempotencyKey(String value) {

    /** Maximum accepted length for a client idempotency key. */
    public static final int MAX_LENGTH = 180;

    /** Rejects missing, blank, or oversized idempotency keys. */
    public IdempotencyKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("idempotency key is required");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("idempotency key must not exceed " + MAX_LENGTH + " characters");
        }
    }
}
