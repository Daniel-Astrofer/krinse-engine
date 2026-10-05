package com.kerosene.kfe.liquidity.domain.policy;

/** Result of a deterministic, idempotent reservation decision. */
public enum LiquidityReservationDecision {
    ACQUIRE,
    RELEASE,
    CONSUME,
    ALREADY_HELD,
    ALREADY_RELEASED,
    ALREADY_CONSUMED,
    INSUFFICIENT_CAPACITY,
    INVALID_REQUEST
}
