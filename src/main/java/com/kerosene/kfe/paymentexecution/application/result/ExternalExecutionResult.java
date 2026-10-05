package com.kerosene.kfe.paymentexecution.application.result;

import java.util.Objects;

/**
 * Provider-neutral observation of one execution attempt.
 * The raw payload and provider reference are retained only for UNKNOWN outcomes as
 * reconciliation data; callers must not use them as log messages.
 * @param outcome attempt classification consumed by the outbox processor
 * @param providerReference provider operation identifier, only when outcome is UNKNOWN
 * @param rawPayload provider response retained for internal reconciliation, only when UNKNOWN
 */
public record ExternalExecutionResult(Outcome outcome, String providerReference, String rawPayload) {
    /** Classification of the external execution attempt. */
    public enum Outcome {
        /** Provider operation completed successfully. */
        COMPLETED,
        /** Worker no longer owns the durable outbox command. */
        CLAIM_LOST,
        /** Provider result is uncertain and requires reconciliation data. */
        UNKNOWN,
        /** Attempt failed in a way the outbox may retry. */
        RETRYABLE_FAILURE,
        /** Provider or preparation rejected the operation permanently. */
        FINAL_FAILURE
    }

    /** Requires a result classification and limits provider evidence to uncertain outcomes. */
    public ExternalExecutionResult {
        Objects.requireNonNull(outcome, "outcome is required");
        if (outcome != Outcome.UNKNOWN && (providerReference != null || rawPayload != null)) {
            throw new IllegalArgumentException("Only an uncertain outcome carries reconciliation data.");
        }
    }

    /** Creates the completed classification without reconciliation fields. */
    /** @return completed result */
    public static ExternalExecutionResult completed() { return new ExternalExecutionResult(Outcome.COMPLETED, null, null); }
    /** Creates the classification used when the worker has lost its lease. */
    /** @return claim-lost result */
    public static ExternalExecutionResult claimLost() { return new ExternalExecutionResult(Outcome.CLAIM_LOST, null, null); }
    /** Creates an uncertain result retaining provider evidence for reconciliation. */
    /** @param reference provider operation reference @param payload raw provider response for internal reconciliation @return unknown result with reconciliation evidence */
    public static ExternalExecutionResult unknown(String reference, String payload) { return new ExternalExecutionResult(Outcome.UNKNOWN, reference, payload); }
    /** Creates a retryable failure classification without provider payload. */
    /** @return retryable failure result */
    public static ExternalExecutionResult retryableFailure() { return new ExternalExecutionResult(Outcome.RETRYABLE_FAILURE, null, null); }
    /** Creates a terminal failure classification without provider payload. */
    /** @return final failure result */
    public static ExternalExecutionResult finalFailure() { return new ExternalExecutionResult(Outcome.FINAL_FAILURE, null, null); }

    /** Returns a fixed operational message for outcomes that require an outcome write. */
    /** @return safe classification message, or null for completed and lost-claim outcomes */
    public String message() {
        return switch (outcome) {
            case UNKNOWN -> "External execution outcome requires reconciliation.";
            case RETRYABLE_FAILURE -> "External execution preparation failed; retry is required.";
            case FINAL_FAILURE -> "External execution preparation was rejected.";
            case COMPLETED, CLAIM_LOST -> null;
        };
    }

    /** Returns only the outcome classification, never provider identifiers or payload. */
    @Override public String toString() { return "ExternalExecutionResult[outcome=" + outcome + "]"; }
}
