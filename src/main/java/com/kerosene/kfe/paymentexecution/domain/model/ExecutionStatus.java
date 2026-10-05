package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.EnumSet;
import java.util.Set;

/** Authoritative payment execution lifecycle independent from persistence and transport. */
public enum ExecutionStatus {
    /** Intent exists but payment validation and authorization preparation has not started. */
    INTENT,
    /** Request is being validated and settlement checks are being evaluated. */
    VALIDATING,
    /** Settlement quorum has passed and the execution is awaiting reservation. */
    QUORUM_SYNC,
    /** Funds/capacity are reserved and the payment is ready for routing or dispatch. */
    LOCKED,
    /** External provider execution has started. */
    EXECUTING,
    /** External transaction was broadcast and awaits chain observation/confirmation. */
    BROADCAST,
    /** External transaction is being monitored for settlement confirmations. */
    CONFIRMING,
    /** Execution completed with its financial effects settled. */
    SETTLED,
    /** Execution ended unsuccessfully without a reconciliation workflow. */
    FAILED,
    /** Execution was explicitly cancelled before irreversible settlement. */
    CANCELLED,
    /** Evidence is insufficient to safely decide whether execution completed. */
    REQUIRES_RECONCILIATION,
    /** Conflicting chain/provider evidence requires resolution. */
    CONFLICTED,
    /** Conflict resolution is actively reconciling competing execution evidence. */
    CONFLICTED_RECONCILING,
    /** A conflicting execution was refunded but remains under reconciliation tracking. */
    CONFLICTED_REFUNDED,
    /** A chain reorganization invalidated a previously settled observation. */
    REORG_RECONCILIATION,
    /** External operation was dropped before settlement and will not be retried automatically. */
    DROPPED,
    /** Execution was abandoned after recovery policy determined no further work is allowed. */
    ABANDONED;

    /** Checks whether the requested next status is legal; same-state transitions are idempotent. */
    /** @param target requested resulting state @return whether the lifecycle allows the transition */
    public boolean canTransitionTo(ExecutionStatus target) {
        if (target == null) {
            return false;
        }
        if (this == target) {
            return true;
        }
        return allowedTargets().contains(target);
    }

    /** Maps detailed lifecycle states to the legacy display status contract. */
    /** @return CONFIRMED for settled, FAILED for terminal failures, otherwise PENDING */
    public String displayStatus() {
        return switch (this) {
            case SETTLED -> "CONFIRMED";
            case FAILED, CANCELLED, REQUIRES_RECONCILIATION, CONFLICTED,
                    CONFLICTED_RECONCILING, CONFLICTED_REFUNDED,
                    REORG_RECONCILIATION, DROPPED, ABANDONED -> "FAILED";
            default -> "PENDING";
        };
    }

    /** Maps to the product-facing status without chain-confirmation context. */
    /** @return product status used by client transaction lists */
    public String productStatus() {
        return productStatus(false);
    }

    /**
     * Maps lifecycle and conflict state to the product-facing status contract.
     * @param hadConfirmations whether the conflicting execution previously had chain confirmations
     * @return NEEDS_REVIEW for confirmed conflicts, otherwise pending/processing/confirming/completed/failed status
     */
    public String productStatus(boolean hadConfirmations) {
        if (this == CONFLICTED && hadConfirmations) {
            return "NEEDS_REVIEW";
        }
        return switch (this) {
            case INTENT -> "PENDING";
            case VALIDATING, QUORUM_SYNC, LOCKED, EXECUTING -> "PROCESSING";
            case BROADCAST, CONFIRMING -> "CONFIRMING";
            case SETTLED, CONFLICTED_REFUNDED -> "COMPLETED";
            case FAILED, CANCELLED, ABANDONED, DROPPED, CONFLICTED -> "FAILED";
            case CONFLICTED_RECONCILING, REQUIRES_RECONCILIATION, REORG_RECONCILIATION ->
                    "NEEDS_REVIEW";
        };
    }

    /** Returns the explicit transition graph for this lifecycle state. */
    /** @return allowed next states; terminal states return an empty set */
    private Set<ExecutionStatus> allowedTargets() {
        return switch (this) {
            case INTENT -> EnumSet.of(VALIDATING, FAILED);
            case VALIDATING -> EnumSet.of(QUORUM_SYNC, FAILED, CANCELLED);
            case QUORUM_SYNC -> EnumSet.of(LOCKED, FAILED, REQUIRES_RECONCILIATION);
            case LOCKED -> EnumSet.of(EXECUTING, SETTLED, FAILED, CANCELLED, REQUIRES_RECONCILIATION);
            case EXECUTING -> EnumSet.of(
                    BROADCAST, SETTLED, FAILED, CONFLICTED_RECONCILING, REQUIRES_RECONCILIATION);
            case BROADCAST, CONFIRMING -> EnumSet.of(
                    CONFIRMING, SETTLED, FAILED, CONFLICTED_RECONCILING, REQUIRES_RECONCILIATION);
            case SETTLED -> EnumSet.of(REORG_RECONCILIATION);
            case CONFLICTED_RECONCILING -> EnumSet.of(FAILED, BROADCAST, REQUIRES_RECONCILIATION);
            case CONFLICTED_REFUNDED -> EnumSet.of(REQUIRES_RECONCILIATION);
            case REORG_RECONCILIATION -> EnumSet.of(SETTLED, FAILED, REQUIRES_RECONCILIATION);
            case REQUIRES_RECONCILIATION -> EnumSet.of(EXECUTING, SETTLED, FAILED);
            case FAILED, CANCELLED, CONFLICTED, DROPPED, ABANDONED -> EnumSet.noneOf(ExecutionStatus.class);
        };
    }
}
