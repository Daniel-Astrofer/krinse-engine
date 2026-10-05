package com.kerosene.kfe.paymentexecution.application.result;

import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

/**
 * Authorized read projection for cancellation hints; excludes raw idempotency keys,
 * invoice payloads, and credentials.
 * @param executionId participant-visible payment identity
 * @param ownerUserId account that owns the execution
 * @param status current execution lifecycle state
 * @param blockchainTransactionId known chain transaction evidence affecting eligibility
 * @param paymentRequest linked user-scoped request metadata, if the execution belongs to one
 */
public record CancellationEligibilitySnapshot(
        PaymentExecutionId executionId, long ownerUserId, ExecutionStatus status,
        String blockchainTransactionId, PaymentRequestCancellationReference paymentRequest) {}
