package com.kerosene.kfe.adapters.in.http.dto.pricing;

import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;

/**
 * Maps internal {@link KfeTransactionStatus} enum values to the canonical 7-state
 * product status vocabulary exposed to clients.
 *
 * <p>Product states: PENDING, PROCESSING, CONFIRMING, COMPLETED, FAILED,
 * NEEDS_REVIEW, REVERSED.</p>
 *
 * <p>Unknown or null values map to PENDING (fail-safe).</p>
 * <p>Reference: {@code docs/ops/PRODUCT_STATE_MAPPING.md}</p>
 */
public final class KfeProductStatusMapper {

    /** Prevents instances because all status conversion operations are static. */
    private KfeProductStatusMapper() {
        // utility class
    }

    /**
     * Maps an internal transaction status to its canonical product status.
     *
     * @param internalStatus the internal {@link KfeTransactionStatus} value
     * @return product status string (one of PENDING, PROCESSING, CONFIRMING, COMPLETED, FAILED, NEEDS_REVIEW, REVERSED)
     */
    public static String toProductStatus(KfeTransactionStatus internalStatus) {
        if (internalStatus == null) {
            return "PENDING";
        }
        return ExecutionStatus.valueOf(internalStatus.name()).productStatus();
    }

    /**
     * Maps an internal transaction status to its canonical product status,
     * with an optional override hint for CONFLICTED.
     *
     * @param internalStatus the internal {@link KfeTransactionStatus} value
     * @param hadConfirmations true if at least 1 confirmation was seen before conflict
     * @return product status string
     */
    public static String toProductStatus(KfeTransactionStatus internalStatus, boolean hadConfirmations) {
        return internalStatus == null
                ? "PENDING"
                : ExecutionStatus.valueOf(internalStatus.name()).productStatus(hadConfirmations);
    }

    /**
     * Maps from a raw string representation of the internal enum name.
     * Safely returns PENDING for null, blank, or unrecognized values.
     *
     * @param rawStatus the raw status string (enum name)
     * @return product status string
     */
    public static String toProductStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return "PENDING";
        }
        try {
            return toProductStatus(KfeTransactionStatus.valueOf(rawStatus.trim().toUpperCase()));
        } catch (IllegalArgumentException ignored) {
            return "PENDING";
        }
    }
}
