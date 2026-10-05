package com.kerosene.kfe.wallet.domain.exception;

/** Signals that a wallet operation violates a domain-level ownership, custody, or lifecycle rule. */
public final class WalletRuleViolation extends IllegalArgumentException {
    /** Creates a rule violation with a safe message suitable for translation by an adapter. */
    public WalletRuleViolation(String message) {
        super(message);
    }
}
