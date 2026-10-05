package com.kerosene.kfe.paymentexecution.domain.model;

/**
 * Stable fingerprint of the fields that determine payment execution semantics.
 * @param value nonblank canonical request digest generated during preflight
 */
public record RequestFingerprint(String value) {
    /** Requires a computed nonblank digest before binding it to an idempotency reservation. */
    public RequestFingerprint {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("request fingerprint is required");
        }
    }
}
