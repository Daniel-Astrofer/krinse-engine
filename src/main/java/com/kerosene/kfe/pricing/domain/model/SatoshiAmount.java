package com.kerosene.kfe.pricing.domain.model;

/**
 * Non-negative amount represented in satoshis, with overflow-checked addition.
 *
 * @param value integer satoshi quantity
 */
public record SatoshiAmount(long value) {

    /** Rejects negative amounts while allowing zero for fee and balance components. */
    public SatoshiAmount {
        if (value < 0L) {
            throw new IllegalArgumentException("satoshi amount must be non-negative");
        }
    }

    /** Constructs a strictly positive amount for user-supplied payment values. */
    public static SatoshiAmount positive(long value) {
        if (value <= 0L) {
            throw new IllegalArgumentException("satoshi amount must be positive");
        }
        return new SatoshiAmount(value);
    }

    /** Adds another amount and throws {@link ArithmeticException} if the sum overflows. */
    public SatoshiAmount plus(SatoshiAmount other) {
        return new SatoshiAmount(Math.addExact(value, other.value));
    }
}
