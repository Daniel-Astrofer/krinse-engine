package com.kerosene.kfe.paymentexecution.domain.policy;

import com.kerosene.kfe.paymentexecution.domain.exception.PaymentCancellationRejected;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;

/** Cancellation is permitted only while there is positive evidence dispatch never started. */
public final class PaymentCancellationPolicy {

    public boolean isClosed(ExecutionStatus status) {
        return switch (status) {
            case SETTLED, FAILED, CANCELLED, CONFLICTED, CONFLICTED_REFUNDED, DROPPED, ABANDONED -> true;
            default -> false;
        };
    }

    public void requireUnstarted(
            ExecutionStatus status, boolean networkEvidence,
            boolean externalOutbound, boolean hasCommand, boolean commandsUntouched) {
        if (isClosed(status)) {
            return;
        }
        boolean localStage = switch (status) {
            case INTENT, VALIDATING, QUORUM_SYNC, LOCKED, EXECUTING -> true;
            default -> false;
        };
        if (!localStage || networkEvidence || !commandsUntouched
                || (externalOutbound && status == ExecutionStatus.EXECUTING && !hasCommand)) {
            throw new PaymentCancellationRejected();
        }
    }
}
