package com.kerosene.kfe.paymentexecution.application.result;

/**
 * Canonical destination fields resolved during preflight; routing may rewrite only these two
 * fields, never identity, value, or credentials.
 * @param externalReference canonical rail destination or request reference
 * @param memo canonicalized payment memo
 */
public record CanonicalPaymentDestination(String externalReference, String memo) {
    /** Redacts destination and memo content from diagnostics. */
    @Override
    public String toString() { return "CanonicalPaymentDestination[REDACTED]"; }
}
