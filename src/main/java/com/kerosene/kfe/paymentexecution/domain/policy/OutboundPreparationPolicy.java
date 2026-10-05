package com.kerosene.kfe.paymentexecution.domain.policy;

import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;

import java.util.Locale;
import java.util.Objects;

/** Preparation decisions only; claims, wallet access, quote validation and effects belong to adapters. */
public final class OutboundPreparationPolicy {

    public enum Action {
        TERMINAL_SKIP,
        INVALID_STATUS,
        ALREADY_BROADCAST,
        RECONCILE_INBOUND,
        UNSUPPORTED,
        EXECUTE
    }

    public record Decision(Action action, String operation) {
    }

    public Decision decide(ExecutionStatus status, String operation, boolean hasBlockchainTxid) {
        Objects.requireNonNull(status, "Execution status is required.");
        String normalized = operation == null ? "" : operation.trim().toUpperCase(Locale.ROOT);
        Action action;
        if (status == ExecutionStatus.SETTLED || status == ExecutionStatus.FAILED) {
            action = Action.TERMINAL_SKIP;
        } else if (status != ExecutionStatus.EXECUTING && status != ExecutionStatus.REQUIRES_RECONCILIATION) {
            action = Action.INVALID_STATUS;
        } else if ("ONCHAIN_OUTBOUND".equals(normalized) && hasBlockchainTxid) {
            action = Action.ALREADY_BROADCAST;
        } else if ("ONCHAIN_INBOUND".equals(normalized) || "LIGHTNING_INBOUND".equals(normalized)) {
            action = Action.RECONCILE_INBOUND;
        } else if ("ONCHAIN_OUTBOUND".equals(normalized) || "LIGHTNING_OUTBOUND".equals(normalized)) {
            action = Action.EXECUTE;
        } else {
            action = Action.UNSUPPORTED;
        }
        return new Decision(action, normalized);
    }

    public Long resolveFeeRate(Long explicitRate, long reservedFeeSats, Long estimatedVbytes) {
        if (reservedFeeSats < 0L) {
            throw new IllegalArgumentException("Reserved network fee must not be negative.");
        }
        if (explicitRate != null && explicitRate > 0L) {
            return explicitRate;
        }
        if (reservedFeeSats == 0L) {
            return explicitRate;
        }
        long vbytes = estimatedVbytes == null ? 180L : estimatedVbytes;
        if (vbytes <= 0L) {
            return explicitRate;
        }
        // Both operands are positive, so quotient plus one cannot overflow when a remainder exists.
        return reservedFeeSats / vbytes + (reservedFeeSats % vbytes == 0L ? 0L : 1L);
    }
}
