package com.kerosene.kfe.liquidity.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Framework-free reservation projection used by liquidity policies and use cases. */
public record LiquidityReservationSnapshot(
        UUID transactionId,
        long amountSats,
        LiquidityReservationState state) {

    public LiquidityReservationSnapshot {
        Objects.requireNonNull(transactionId, "transactionId is required");
        Objects.requireNonNull(state, "state is required");
        if (amountSats <= 0) {
            throw new IllegalArgumentException("reservation amount must be positive");
        }
    }
}
