package com.kerosene.kfe.paymentexecution.domain.model;

import com.kerosene.kfe.paymentexecution.domain.exception.IdempotencyKeyConflict;
import com.kerosene.kfe.paymentexecution.domain.exception.PaymentExecutionStillProcessing;

/** Domain representation of a client idempotency reservation. */
public final class IdempotencyReservation {

    /** Client key uniquely reserved within the owning account. */
    private final IdempotencyKey key;
    /** Fingerprint preventing the same key from being reused for different payment semantics. */
    private final RequestFingerprint fingerprint;
    /** Completed execution identity, or null while the winning request is still processing. */
    private PaymentExecutionId executionId;

    /** Reconstitutes a reservation after enforcing key and fingerprint presence. */
    /** @param key account-scoped idempotency key @param fingerprint canonical request fingerprint @param executionId completed execution, or null for a pending reservation */
    private IdempotencyReservation(
            IdempotencyKey key,
            RequestFingerprint fingerprint,
            PaymentExecutionId executionId) {
        if (key == null || fingerprint == null) {
            throw new IllegalArgumentException("idempotency key and request fingerprint are required");
        }
        this.key = key;
        this.fingerprint = fingerprint;
        this.executionId = executionId;
    }

    /** Creates a newly reserved request that has not completed its execution yet. */
    /** @param key reserved key @param fingerprint canonical request fingerprint @return pending reservation */
    public static IdempotencyReservation pending(IdempotencyKey key, RequestFingerprint fingerprint) {
        return new IdempotencyReservation(key, fingerprint, null);
    }

    /** Restores persisted reservation state, including its optional completed execution. */
    /** @param key persisted key @param fingerprint persisted request fingerprint @param executionId linked execution or null while pending @return restored reservation */
    public static IdempotencyReservation reconstitute(
            IdempotencyKey key,
            RequestFingerprint fingerprint,
            PaymentExecutionId executionId) {
        return new IdempotencyReservation(key, fingerprint, executionId);
    }

    /** Rejects reuse of the same key for a different canonical request. */
    /** @param candidate fingerprint of the new request @throws IdempotencyKeyConflict when fingerprints differ */
    public void assertSameRequest(RequestFingerprint candidate) {
        if (!fingerprint.equals(candidate)) {
            throw new IdempotencyKeyConflict();
        }
    }

    /** Returns the completed execution identity or raises the established retryable processing error. */
    /** @return execution linked to this reservation @throws PaymentExecutionStillProcessing while pending */
    public PaymentExecutionId completedExecutionId() {
        if (executionId == null) {
            throw new PaymentExecutionStillProcessing();
        }
        return executionId;
    }

    /** Reports whether this reservation has not yet been linked to an execution. */
    /** @return true when the execution identity is absent */
    public boolean isPending() {
        return executionId == null;
    }

    /** Completes the reservation idempotently, rejecting attempts to link another execution. */
    /** @param completedExecutionId execution that won this reservation @throws IdempotencyKeyConflict when already linked to a different execution */
    public void complete(PaymentExecutionId completedExecutionId) {
        if (completedExecutionId == null) {
            throw new IllegalArgumentException("completed execution id is required");
        }
        if (executionId != null && !executionId.equals(completedExecutionId)) {
            throw new IdempotencyKeyConflict();
        }
        executionId = completedExecutionId;
    }

    /** Returns the reserved client idempotency key. */
    public IdempotencyKey key() {
        return key;
    }

    /** Returns the canonical request fingerprint bound to the key. */
    public RequestFingerprint fingerprint() {
        return fingerprint;
    }
}
