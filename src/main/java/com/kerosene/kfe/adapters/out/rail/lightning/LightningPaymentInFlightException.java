package com.kerosene.kfe.adapters.out.rail.lightning;

/**
 * Payment left LND in a non-terminal state — must not settle or permanently fail the reserve.
 * Outbox processor maps this to REQUIRES_RECONCILIATION / retry path.
 */
public class LightningPaymentInFlightException extends RuntimeException {

    /** Provider-side payment reference required to query or reconcile the unfinished payment. */
    private final String providerReference;
    /** Optional raw provider response retained for operational reconciliation. */
    private final String rawPayload;

    /**
     * Creates a signal that submission started but has not reached a terminal payment state.
     *
     * @param message explanation surfaced to the caller or outbox diagnostics
     * @param providerReference provider identifier used for later status reconciliation
     * @param rawPayload provider response retained as reconciliation evidence
     */
    public LightningPaymentInFlightException(
            String message, String providerReference, String rawPayload) {
        super(message);
        this.providerReference = providerReference;
        this.rawPayload = rawPayload;
    }

    /** @return provider reference needed to reconcile the in-flight payment */
    public String providerReference() {
        return providerReference;
    }

    /** @return raw provider response captured when the payment became in-flight */
    public String rawPayload() {
        return rawPayload;
    }
}
