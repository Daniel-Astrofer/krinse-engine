package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.UUID;

/**
 * Validated initial intent, before pricing, authorization gate, reservations, or execution.
 * @param userId authenticated account identifier
 * @param idempotencyKey key making creation retries stable
 * @param rail payment rail selected by the request
 * @param direction transfer direction for the rail
 * @param sourceWalletId optional source wallet identifier
 * @param destinationWalletId optional destination wallet before resolution
 * @param amountSats positive principal in integer satoshis
 * @param externalReference destination or public request reference
 * @param memo optional transfer memo
 */
public record PaymentIntent(
        long userId,
        IdempotencyKey idempotencyKey,
        PaymentRail rail,
        PaymentDirection direction,
        UUID sourceWalletId,
        UUID destinationWalletId,
        long amountSats,
        String externalReference,
        String memo) {

    /** Maximum principal supported by the domain, expressed as integer satoshis. */
    private static final long MAX_SATOSHIS = 2_100_000_000_000_000L;

    /** Enforces account, routing, rail/direction, and monetary bounds at intent creation. */
    public PaymentIntent {
        if (userId <= 0L) {
            throw new IllegalArgumentException("authenticated user id must be positive");
        }
        if (idempotencyKey == null || rail == null || direction == null) {
            throw new IllegalArgumentException("idempotency key, rail and direction are required");
        }
        if (amountSats <= 0L) {
            throw new IllegalArgumentException("amountSats must be positive.");
        }
        if (amountSats > MAX_SATOSHIS) {
            throw new IllegalArgumentException("amountSats exceeds maximum allowed limit (21M BTC).");
        }
        if (rail == PaymentRail.INTERNAL && direction != PaymentDirection.INTERNAL) {
            throw new IllegalArgumentException("INTERNAL rail requires INTERNAL direction.");
        }
        if (direction == PaymentDirection.INTERNAL && rail != PaymentRail.INTERNAL) {
            throw new IllegalArgumentException("INTERNAL direction requires INTERNAL rail.");
        }
    }

    /** Returns non-secret intent metadata while redacting external references and memo. */
    @Override
    public String toString() {
        return "PaymentIntent[userId=" + userId + ", rail=" + rail + ", direction=" + direction
                + ", amountSats=" + amountSats + ", references=REDACTED]";
    }
}
