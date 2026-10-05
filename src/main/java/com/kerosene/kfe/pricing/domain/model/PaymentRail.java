package com.kerosene.kfe.pricing.domain.model;

/** Financial route used to execute a payment. */
public enum PaymentRail {
    /** Internal ledger transfer that does not incur external network or platform fees here. */
    INTERNAL,
    /** Bitcoin on-chain transfer subject to chain fee estimation. */
    ONCHAIN,
    /** Lightning transfer priced using the Lightning rail policy and fee source. */
    LIGHTNING
}
