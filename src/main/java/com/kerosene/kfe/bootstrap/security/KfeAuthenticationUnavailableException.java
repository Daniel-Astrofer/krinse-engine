package com.kerosene.kfe.bootstrap.security;

/**
 * Signals that a required authentication dependency is unavailable, so validity cannot be decided.
 * The HTTP filter maps this condition to service unavailable rather than rejecting the token.
 */
final class KfeAuthenticationUnavailableException extends RuntimeException {
    /** Creates the sanitized dependency-unavailable signal without retaining remote details. */
    KfeAuthenticationUnavailableException() {
        super("Authentication dependency unavailable");
    }

    /**
     * Creates the dependency-unavailable signal while preserving the underlying cause for diagnostics.
     *
     * @param cause failure raised by the authentication dependency
     */
    KfeAuthenticationUnavailableException(Throwable cause) {
        super("Authentication dependency unavailable", cause);
    }
}
