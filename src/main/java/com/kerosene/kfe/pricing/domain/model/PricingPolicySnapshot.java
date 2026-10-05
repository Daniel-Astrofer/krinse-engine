package com.kerosene.kfe.pricing.domain.model;

/**
 * Immutable, versioned policy input used for one pricing decision.
 *
 * @param version positive policy revision captured for the quote
 * @param railPricing fee settings for the selected rail and direction
 */
public record PricingPolicySnapshot(int version, RailPricing railPricing) {

    /** Rejects non-positive policy versions so a quote can be tied to an identifiable revision. */
    public PricingPolicySnapshot {
        if (version < 1) {
            throw new IllegalArgumentException("pricing policy version must be positive");
        }
    }

    /**
     * Platform fee configuration with optional lower and upper satoshi clamps.
     *
     * @param basisPoints fee rate in basis points, from 0 through 10,000
     * @param minSats optional minimum fee in satoshis
     * @param maxSats optional maximum fee in satoshis
     */
    public record RailPricing(int basisPoints, Long minSats, Long maxSats) {
        /** Validates rate bounds, nonnegative clamps, and minimum-not-above-maximum ordering. */
        public RailPricing {
            if (basisPoints < 0 || basisPoints > 10_000) {
                throw new IllegalArgumentException("basisPoints must be between 0 and 10000");
            }
            if (minSats != null && minSats < 0L) {
                throw new IllegalArgumentException("minSats must be non-negative");
            }
            if (maxSats != null && maxSats < 0L) {
                throw new IllegalArgumentException("maxSats must be non-negative");
            }
            if (minSats != null && maxSats != null && minSats > maxSats) {
                throw new IllegalArgumentException("minSats must not exceed maxSats");
            }
        }
    }
}
