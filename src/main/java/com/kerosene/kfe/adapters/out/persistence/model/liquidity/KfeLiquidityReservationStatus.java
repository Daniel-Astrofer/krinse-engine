package com.kerosene.kfe.adapters.out.persistence.model.liquidity;

/** States describing whether a reserved amount of Lightning liquidity remains held. */
public enum KfeLiquidityReservationStatus {
    /** Capacity is locked while the related Lightning payment is unresolved. */
    HELD,
    /** Payment failed or was released, so the reserved capacity is available again. */
    RELEASED,
    /** Payment succeeded and the reserved capacity left the node through the HTLC. */
    CONSUMED
}
