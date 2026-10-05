package com.kerosene.kfe.liquidity.domain.policy;

import com.kerosene.kfe.liquidity.domain.model.LiquidityReservationSnapshot;
import com.kerosene.kfe.liquidity.domain.model.LiquidityReservationState;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LiquidityReservationPolicyTest {
    private final LiquidityReservationPolicy policy = new LiquidityReservationPolicy();

    @Test
    void reserveIsIdempotentForSameHeldAmount() {
        var current = new LiquidityReservationSnapshot(UUID.randomUUID(), 100, LiquidityReservationState.HELD);
        assertThat(policy.reserve(current, 0, 100)).isEqualTo(LiquidityReservationDecision.ALREADY_HELD);
        assertThat(policy.reserve(current, 0, 101)).isEqualTo(LiquidityReservationDecision.INVALID_REQUEST);
    }

    @Test
    void reservationNeverReopensTerminalState() {
        var released = new LiquidityReservationSnapshot(UUID.randomUUID(), 100, LiquidityReservationState.RELEASED);
        var consumed = new LiquidityReservationSnapshot(UUID.randomUUID(), 100, LiquidityReservationState.CONSUMED);
        assertThat(policy.reserve(released, 1_000, 100)).isEqualTo(LiquidityReservationDecision.ALREADY_RELEASED);
        assertThat(policy.reserve(consumed, 1_000, 100)).isEqualTo(LiquidityReservationDecision.ALREADY_CONSUMED);
    }

    @Test
    void capacityMustCoverNewReservation() {
        assertThat(policy.reserve(null, 99, 100)).isEqualTo(LiquidityReservationDecision.INSUFFICIENT_CAPACITY);
        assertThat(policy.reserve(null, 100, 100)).isEqualTo(LiquidityReservationDecision.ACQUIRE);
    }
}
