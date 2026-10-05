package com.kerosene.kfe.ledger.domain;

/** Canonical movement vocabulary. A posting is immutable once accepted. */
public enum LedgerMovementType {
    /** Transfers funds from available into the locked reservation bucket. */
    RESERVE,
    /** Releases reserved funds from locked back into available. */
    RELEASE_RESERVE,
    /** Consumes locked funds to settle an external or internal debit. */
    SETTLE_DEBIT,
    /** Adds an ordinary credit, applying outstanding reorganization debt first. */
    CREDIT,
    /** Credits a platform fee or other separately accounted fee amount. */
    CREDIT_FEE,
    /** Compensating credit that restores value or pays down reorganization debt. */
    COMPENSATING_CREDIT,
    /** Compensating debit that reverses a previously recognized credit. */
    COMPENSATING_DEBIT
}
