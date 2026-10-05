package com.kerosene.kfe.adapters.out.rail.shared;

/**
 * Namespace for exception types shared by external rail adapters.
 */
public final class KfeRailException {

    /** Prevents instantiation of this exception namespace. */
    private KfeRailException() {
    }

    /** Indicates that an external financial provider is disabled or unavailable for an operation. */
    public static class ProviderUnavailable extends RuntimeException {
        /**
         * Creates an unavailable-provider failure with a human-readable explanation.
         *
         * @param message safe diagnostic describing the unavailable integration
         */
        public ProviderUnavailable(String message) {
            super(message);
        }

        /**
         * Creates an unavailable-provider failure and retains its underlying cause.
         *
         * @param message safe diagnostic describing the failed provider operation
         * @param cause original transport, configuration, or provider failure
         */
        public ProviderUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
