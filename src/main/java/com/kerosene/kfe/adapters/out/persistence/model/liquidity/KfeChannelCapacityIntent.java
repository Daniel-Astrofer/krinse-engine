package com.kerosene.kfe.adapters.out.persistence.model.liquidity;

/** Operation requested by a channel-capacity job. */
public enum KfeChannelCapacityIntent {
    /** Request creation of a channel with the requested local capacity. */
    OPEN,
    /** Request closure of the identified channel and recovery of its capacity. */
    CLOSE
}
