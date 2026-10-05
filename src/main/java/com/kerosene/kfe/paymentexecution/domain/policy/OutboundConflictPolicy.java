package com.kerosene.kfe.paymentexecution.domain.policy;

import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;

import java.util.Objects;
import java.util.Optional;

/** A conflict or disappearance alone never authorizes releasing a reserve or adopting a replacement. */
public final class OutboundConflictPolicy {

    public Optional<ExecutionStatus> target(ExecutionStatus current) {
        Objects.requireNonNull(current, "Current execution status is required.");
        return switch (current) {
            case FAILED, CANCELLED, CONFLICTED_REFUNDED, DROPPED, ABANDONED -> Optional.empty();
            case SETTLED, REORG_RECONCILIATION -> Optional.of(ExecutionStatus.REORG_RECONCILIATION);
            default -> Optional.of(ExecutionStatus.REQUIRES_RECONCILIATION);
        };
    }
}
