package com.kerosene.kfe.ledger.domain;

/** Buckets that make up a wallet's custodial ledger balance. */
public enum LedgerBucket {
    /** Funds currently spendable for new transactions. */
    AVAILABLE,
    /** Funds observed or credited provisionally before release to available balance. */
    PENDING,
    /** Funds reserved for an in-flight operation or awaiting final debit settlement. */
    LOCKED,
    /** Funds withheld by an automatic risk or policy control. */
    AUTO_HOLD,
    /** Absolute quantity reported by an external chain or provider, not a user liability bucket. */
    OBSERVED
}
