package com.kerosene.kfe.liquidity.domain.policy;

import com.kerosene.kfe.liquidity.domain.model.LiquidityReservationSnapshot;
import com.kerosene.kfe.liquidity.domain.model.LiquidityReservationState;

/**
 * Pure liquidity rules. It deliberately does not know about a database, a channel
 * gateway, or a transaction boundary. The caller owns the atomic compare-and-set.
 */
public final class LiquidityReservationPolicy {

    public LiquidityReservationDecision reserve(
            LiquidityReservationSnapshot current,
            long freeCapacitySats,
            long requestedSats) {
        if (requestedSats <= 0 || freeCapacitySats < 0) {
            return LiquidityReservationDecision.INVALID_REQUEST;
        }
        if (current != null) {
            return switch (current.state()) {
                case HELD -> current.amountSats() == requestedSats
                        ? LiquidityReservationDecision.ALREADY_HELD
                        : LiquidityReservationDecision.INVALID_REQUEST;
                case RELEASED -> LiquidityReservationDecision.ALREADY_RELEASED;
                case CONSUMED -> LiquidityReservationDecision.ALREADY_CONSUMED;
            };
        }
        return freeCapacitySats >= requestedSats
                ? LiquidityReservationDecision.ACQUIRE
                : LiquidityReservationDecision.INSUFFICIENT_CAPACITY;
    }

    public LiquidityReservationDecision release(LiquidityReservationSnapshot current) {
        if (current == null) {
            return LiquidityReservationDecision.INVALID_REQUEST;
        }
        return switch (current.state()) {
            case HELD -> LiquidityReservationDecision.RELEASE;
            case RELEASED -> LiquidityReservationDecision.ALREADY_RELEASED;
            case CONSUMED -> LiquidityReservationDecision.ALREADY_CONSUMED;
        };
    }

    public LiquidityReservationDecision consume(LiquidityReservationSnapshot current) {
        if (current == null) {
            return LiquidityReservationDecision.INVALID_REQUEST;
        }
        return switch (current.state()) {
            case HELD -> LiquidityReservationDecision.CONSUME;
            case RELEASED -> LiquidityReservationDecision.ALREADY_RELEASED;
            case CONSUMED -> LiquidityReservationDecision.ALREADY_CONSUMED;
        };
    }

    public LiquidityReservationState terminalState(boolean consumed) {
        return consumed ? LiquidityReservationState.CONSUMED : LiquidityReservationState.RELEASED;
    }
}
