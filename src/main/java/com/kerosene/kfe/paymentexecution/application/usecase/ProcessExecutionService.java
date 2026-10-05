package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionClaimPort;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionOutcomePort;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionPreparationPort;
import com.kerosene.kfe.paymentexecution.application.port.out.ExternalExecutionPort;
import com.kerosene.kfe.paymentexecution.domain.exception.ExecutionClaimLost;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;

import java.util.List;
import java.util.Objects;

/** Coordinates committed preparation and external execution without owning a transaction. */
public final class ProcessExecutionService {
    /** Renews ownership while the claimed outbox operation is being processed. */
    private final ExecutionClaimPort claims;
    /** Loads committed execution context and verifies it is eligible for dispatch. */
    private final ExecutionPreparationPort preparation;
    /** Ordered rail executors searched for one that supports the prepared operation. */
    private final List<ExternalExecutionPort> executors;
    /** Persists terminal, unknown, or retryable provider outcomes. */
    private final ExecutionOutcomePort outcomes;

    /**
     * Creates a transaction-free worker coordinator with a fixed executor list.
     * @param claims outbox claim and heartbeat port
     * @param preparation committed execution preparation port
     * @param executors external rail executors to select from
     * @param outcomes durable outcome writer
     */
    public ProcessExecutionService(
            ExecutionClaimPort claims,
            ExecutionPreparationPort preparation,
            List<ExternalExecutionPort> executors,
            ExecutionOutcomePort outcomes) {
        this.claims = Objects.requireNonNull(claims, "execution claims are required");
        this.preparation = Objects.requireNonNull(preparation, "execution preparation is required");
        this.executors = List.copyOf(executors);
        this.outcomes = Objects.requireNonNull(outcomes, "execution outcomes are required");
    }

    /**
     * Heartbeats the claim, prepares committed state, dispatches to the matching rail adapter,
     * then persists the result classification. Outcome persistence errors propagate so they
     * cannot trigger a second executor or silently lose the provider result.
     * @param claim lease identifying the claimed outbox operation
     * @throws ExecutionClaimLost when the first ownership heartbeat fails
     */
    public void process(ExecutionClaim claim) {
        Objects.requireNonNull(claim, "execution claim is required");
        if (!claims.heartbeat(claim)) {
            throw new ExecutionClaimLost(claim.outboxId());
        }

        var prepared = Objects.requireNonNull(preparation.prepare(claim), "preparation result is required");
        if (prepared.isEmpty()) {
            return;
        }
        if (!claims.heartbeat(claim)) {
            return;
        }

        var context = prepared.get();
        var executor = executors.stream()
                .filter(candidate -> candidate.supports(context.operation()))
                .findFirst()
                .orElse(null);
        if (executor == null) {
            outcomes.markFinalFailure(claim, context.transactionId(),
                    "UNSUPPORTED_OPERATION", "Unsupported KFE outbox operation.");
            return;
        }

        // Infrastructure errors propagate: a failed outcome write must never trigger a fallback.
        var result = Objects.requireNonNull(executor.execute(claim, context), "external execution result is required");
        switch (result.outcome()) {
            case COMPLETED, CLAIM_LOST -> { }
            case UNKNOWN -> outcomes.markUnknown(claim, context.transactionId(),
                    result.providerReference(), result.rawPayload(), result.message());
            case RETRYABLE_FAILURE -> outcomes.markRetryableFailure(claim, context.transactionId(),
                    "PROVIDER_RETRYABLE_FAILURE", result.message());
            case FINAL_FAILURE -> outcomes.markFinalFailure(claim, context.transactionId(),
                    "PROVIDER_FINAL_FAILURE", result.message());
        }
    }
}
