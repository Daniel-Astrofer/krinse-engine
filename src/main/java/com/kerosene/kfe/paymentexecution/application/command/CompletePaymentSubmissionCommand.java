package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.RequestFingerprint;
import java.util.Objects;

/**
 * Final step of the same authorized submission, not an authorization or resumption ticket.
 * @param userId authenticated account that owns the submission
 * @param executionId persisted payment execution being completed
 * @param idempotencyKey key reserved for the current submission
 * @param fingerprint canonical request fingerprint bound to that reservation
 */
public record CompletePaymentSubmissionCommand(long userId, PaymentExecutionId executionId,
        IdempotencyKey idempotencyKey, RequestFingerprint fingerprint) {
    /** Requires account, execution, idempotency, and fingerprint identities. */
    public CompletePaymentSubmissionCommand {
        if (userId <= 0L) { throw new IllegalArgumentException("authenticated user id must be positive"); }
        Objects.requireNonNull(executionId, "execution id is required");
        Objects.requireNonNull(idempotencyKey, "idempotency key is required");
        Objects.requireNonNull(fingerprint, "request fingerprint is required");
    }
    /** Returns the execution identity while redacting idempotency material. */
    @Override public String toString() {
        return "CompletePaymentSubmissionCommand[userId=" + userId + ", executionId=" + executionId + ", idempotency=REDACTED]";
    }
}
