package com.kerosene.kfe.ledger.domain;

/** Raised when a ledger command would break a monetary invariant. */
public class LedgerInvariantViolation extends IllegalArgumentException {
    /**
     * Creates a named ledger-domain validation failure for an invalid balance or posting transition.
     *
     * @param message explanation suitable for diagnostics and application error translation
     */
    public LedgerInvariantViolation(String message) {
        super(message);
    }
}
