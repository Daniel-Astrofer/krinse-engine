package com.kerosene.kfe.adapters.out.persistence.model.paymentexecution;

import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;

/**
 * Detailed persisted lifecycle states for financial transaction execution.
 *
 * <p>The values cover intent creation through rail execution, settlement, exceptional chain
 * outcomes, and explicit reconciliation states. A user-facing coarse label is derived separately
 * so transaction history ordering and the underlying state machine remain unchanged.</p>
 */
public enum KfeTransactionStatus {
    /** Transaction intent has been recorded but validation has not begun. */
    INTENT,
    /** Request parameters, authorization, and policy constraints are being checked. */
    VALIDATING,
    /** Required financial quorum or distributed policy agreement is being synchronized. */
    QUORUM_SYNC,
    /** Funds or execution capacity are locked while the operation is prepared. */
    LOCKED,
    /** The selected payment rail is currently executing the transaction. */
    EXECUTING,
    /** The transaction was broadcast and awaits network confirmation or provider settlement. */
    BROADCAST,
    /** A broadcast on-chain transaction is accumulating confirmations. */
    CONFIRMING,
    /** The transaction reached its successful terminal settlement state. */
    SETTLED,
    /** Execution ended with a terminal failure. */
    FAILED,
    /** The transaction was cancelled before successful settlement. */
    CANCELLED,
    /** Available evidence is insufficient or inconsistent and reconciliation is required. */
    REQUIRES_RECONCILIATION,
    /** Conflicting observations exist for the transaction's execution or chain state. */
    CONFLICTED,
    /** A conflicting transaction is actively being resolved. */
    CONFLICTED_RECONCILING,
    /** Conflict resolution determined that the transaction value was refunded. */
    CONFLICTED_REFUNDED,
    /** A chain reorganization requires recomputing the transaction's financial state. */
    REORG_RECONCILIATION,
    /** The transaction was dropped from the active network/provider processing view. */
    DROPPED,
    /** The transaction was abandoned and will not continue through normal execution. */
    ABANDONED;

    /**
     * Maps this detailed state to the shared coarse status used by UI badges.
     *
     * <p>The result is display-only; it does not alter the persisted state or transaction date and
     * ordering. The mapping is delegated to the domain {@link ExecutionStatus} contract.</p>
     *
     * @return coarse status label such as pending, confirmed, or failed
     */
    public String displayStatus() {
        return ExecutionStatus.valueOf(name()).displayStatus();
    }

    /**
     * Returns a coarse label for a nullable transaction status.
     *
     * @param status detailed state to map; {@code null} is treated as pending
     * @return display label, defaulting to {@code PENDING} for a missing state
     */
    public static String displayStatusOf(KfeTransactionStatus status) {
        return status == null ? "PENDING" : status.displayStatus();
    }

    /**
     * Parses and maps a persisted status string without propagating unknown-value errors.
     *
     * @param rawStatus stored enum name; surrounding whitespace and letter case are ignored
     * @return mapped display label, or {@code PENDING} when the input is blank or unknown
     */
    public static String displayStatusOf(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return "PENDING";
        }
        try {
            return KfeTransactionStatus.valueOf(rawStatus.trim().toUpperCase()).displayStatus();
        } catch (IllegalArgumentException ignored) {
            return "PENDING";
        }
    }
}
