package com.kerosene.kfe.pricing.domain.model;

/** Direction of value movement from the KFE point of view. */
public enum PaymentDirection {
    /** Value moves into a recipient wallet; policy may deduct its fee from the received amount. */
    INBOUND,
    /** Value leaves a sender wallet; policy adds applicable fees to the sender's debit. */
    OUTBOUND,
    /** Value moves between internal ledgers and is exempt from external rail fees in this calculator. */
    INTERNAL
}
