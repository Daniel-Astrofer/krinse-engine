package com.kerosene.kfe.liquidity.domain.model;

/** Lifecycle of a capacity reservation. Terminal states are never reopened. */
public enum LiquidityReservationState {
    HELD,
    RELEASED,
    CONSUMED
}
