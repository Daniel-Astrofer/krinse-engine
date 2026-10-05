package com.kerosene.kfe.adapters.out.rail.lightning;

/**
 * Normalized LND / Lightning payment terminal classification for binary settlement.
 */
public enum LightningPaymentOutcome {
    /** Provider confirms a terminal successful payment. */
    SUCCEEDED,
    /** Provider confirms a terminal failed or cancelled payment. */
    FAILED,
    /** Provider reports an active, pending, or submitted payment that still needs reconciliation. */
    IN_FLIGHT,
    /** Provider status is absent or not understood; terminal outcome cannot be inferred. */
    UNKNOWN;

    /**
     * Maps common provider status spellings into the settlement-facing outcome vocabulary.
     * Unrecognized values remain UNKNOWN to avoid incorrectly settling or failing a payment.
     *
     * @param status raw status string from LND or another Lightning provider
     * @return normalized outcome, with null, blank, and unknown values mapped to UNKNOWN
     */
    public static LightningPaymentOutcome fromProviderStatus(String status) {
        if (status == null || status.isBlank()) {
            return UNKNOWN;
        }
        String normalized = status.trim().toUpperCase();
        return switch (normalized) {
            case "SUCCEEDED", "SUCCESS", "COMPLETE", "COMPLETED", "PAID", "SETTLED" -> SUCCEEDED;
            case "FAILED", "FAILURE", "ERROR", "CANCELLED", "CANCELED", "TIMEOUT" -> FAILED;
            case "IN_FLIGHT", "INFLIGHT", "PENDING", "IN_PROGRESS", "SENDING", "SUBMITTED" -> IN_FLIGHT;
            default -> UNKNOWN;
        };
    }
}
