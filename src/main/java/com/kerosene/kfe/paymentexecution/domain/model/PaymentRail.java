package com.kerosene.kfe.paymentexecution.domain.model;

/** Settlement network or ledger path used to execute a payment. */
public enum PaymentRail {
    /** Transfer settled by KFE's internal ledger. */
    INTERNAL,
    /** Bitcoin on-chain transfer. */
    ONCHAIN,
    /** Lightning Network transfer. */
    LIGHTNING
}
