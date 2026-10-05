package com.kerosene.kfe.paymentexecution.application.result;

import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.domain.model.RequestFingerprint;
import java.util.Objects;
import java.util.Optional;

/**
 * Immediate orchestration output, not a durable or reusable authorization grant.
 * @param command canonicalized payment command after destination resolution
 * @param fingerprint stable digest of payment semantics used for idempotency
 * @param existingPayment completed matching result when the request is a replay
 */
public record PaymentPreflightResult(SubmitPaymentCommand command, RequestFingerprint fingerprint,
        Optional<PaymentExecutionResult> existingPayment) {
    /** Requires canonical command, request fingerprint, and explicit replay lookup result. */
    public PaymentPreflightResult {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(existingPayment, "existingPayment");
    }

    /** Returns only replay presence and redacts all payment and authorization fields. */
    @Override
    public String toString() { return "PaymentPreflightResult[replay=" + existingPayment.isPresent() + ", REDACTED]"; }
}
