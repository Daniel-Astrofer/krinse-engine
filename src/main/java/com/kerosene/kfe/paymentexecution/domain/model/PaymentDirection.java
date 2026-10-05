package com.kerosene.kfe.paymentexecution.domain.model;

/** Money-flow orientation from the authenticated account's perspective. */
public enum PaymentDirection {
    /** Value is received into a wallet owned by the authenticated account. */
    INBOUND,
    /** Value leaves a wallet owned by the authenticated account. */
    OUTBOUND,
    /** Ledger value moves between participants inside KFE. */
    INTERNAL
}
