package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

/**
 * Internal submit context for routing an already locked execution; amount and authoritative
 * routing state are loaded from that execution, not supplied by this command.
 * @param userId authenticated account identifier that owns the submission
 * @param executionId locked execution to route
 * @param externalReference canonical reference previously authorized during preflight
 * @param memo canonical memo previously authorized during preflight
 * @param feeRateSatPerVbyte optional on-chain fee rate used for dispatch metadata
 * @param feeTargetBlocks optional on-chain confirmation target used for dispatch metadata
 */
public record RouteLockedPaymentCommand(long userId, PaymentExecutionId executionId,
        String externalReference, String memo, Long feeRateSatPerVbyte, Integer feeTargetBlocks) {
    /** Ensures the command always identifies an authenticated user and persisted execution. */
    public RouteLockedPaymentCommand {
        if (userId <= 0L || executionId == null) {
            throw new IllegalArgumentException("authenticated user and execution id are required");
        }
    }

    /** Returns routing identifiers while redacting canonical reference and memo values. */
    @Override
    public String toString() {
        return "RouteLockedPaymentCommand[userId=" + userId + ", executionId=" + executionId + ", references=REDACTED]";
    }
}
