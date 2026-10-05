package com.kerosene.kfe.paymentexecution.domain.model;

import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;
import com.kerosene.kfe.paymentexecution.domain.exception.InvalidPaymentExecutionTransition;

/** Aggregate root that owns the payment execution lifecycle invariant. */
public final class PaymentExecution {

    /** Stable execution identity owned by this lifecycle aggregate. */
    private final PaymentExecutionId id;
    /** Current authoritative lifecycle state. */
    private ExecutionStatus status;

    /** Restores an aggregate only when both identity and persisted lifecycle state are present. */
    /** @param id stable execution identity @param status persisted authoritative lifecycle state */
    private PaymentExecution(PaymentExecutionId id, ExecutionStatus status) {
        if (id == null || status == null) {
            throw new IllegalArgumentException("payment execution id and status are required");
        }
        this.id = id;
        this.status = status;
    }

    /** Reconstitutes a lifecycle aggregate from its persisted identity and current status. */
    /** @param id persisted execution identity @param status persisted current lifecycle state @return restored aggregate */
    public static PaymentExecution reconstitute(PaymentExecutionId id, ExecutionStatus status) {
        return new PaymentExecution(id, status);
    }

    /** Applies a legal lifecycle transition and returns its immutable audit event. */
    /** @param target requested next state @return event containing previous and current states @throws InvalidPaymentExecutionTransition when the transition is forbidden */
    public PaymentExecutionStatusChanged transitionTo(ExecutionStatus target) {
        ExecutionStatus previous = status;
        if (!previous.canTransitionTo(target)) {
            throw new InvalidPaymentExecutionTransition(previous, target);
        }
        status = target;
        return new PaymentExecutionStatusChanged(id, previous, status);
    }

    /** Preliminary eligibility only; cancellation must also revalidate dispatch evidence under locks. */
    /** Provides preliminary cancellation eligibility; dispatch evidence must be rechecked under locks. */
    /** @param blockchainTransactionId known external chain transaction identifier, if any @return true when current state permits a cancellation attempt */
    public boolean canBeCancelled(String blockchainTransactionId) {
        return switch (status) {
            case INTENT, VALIDATING, QUORUM_SYNC, LOCKED -> true;
            case EXECUTING -> blockchainTransactionId == null || blockchainTransactionId.isBlank();
            default -> false;
        };
    }

    /** Whether cancellation side effects may still close this execution. */
    /** Reports whether cancellation and reconciliation effects may still close this execution. */
    /** @return false for completed or terminal executions */
    public boolean isIncomplete() {
        return switch (status) {
            case SETTLED, FAILED, CANCELLED, CONFLICTED, CONFLICTED_REFUNDED, DROPPED, ABANDONED -> false;
            default -> true;
        };
    }

    /** Returns this aggregate's stable identity. */
    public PaymentExecutionId id() {
        return id;
    }

    /** Returns this aggregate's current authoritative lifecycle state. */
    public ExecutionStatus status() {
        return status;
    }
}
