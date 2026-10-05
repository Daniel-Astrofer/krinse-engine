package com.kerosene.kfe.adapters.out.persistence.model.liquidity;

/** Operation category evaluated and recorded by channel lifecycle policy. */
public enum KfeChannelOperationType {
    /** Create a Lightning channel with a requested local capacity. */
    OPEN,
    /** Move liquidity through an existing channel to improve its balance distribution. */
    REBALANCE,
    /** Close an existing channel and return its remaining on-chain value. */
    CLOSE,
    /** Adjust the channel's fee rate in parts per million. */
    PPM_ADJUST
}
