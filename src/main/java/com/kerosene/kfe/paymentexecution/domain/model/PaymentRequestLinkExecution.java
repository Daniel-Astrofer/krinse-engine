package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Payer-owned execution reloaded before attaching it to the recipient's request.
 * @param executionId persisted payment identity
 * @param userId payer account identifier
 * @param status execution lifecycle state
 * @param rail execution rail
 * @param direction execution direction
 * @param destinationWalletId recipient wallet selected for the execution
 * @param grossAmountSats gross amount in integer satoshis
 * @param externalReference public request identifier bound to the execution
 */
public record PaymentRequestLinkExecution(PaymentExecutionId executionId, long userId, ExecutionStatus status,
        PaymentRail rail, PaymentDirection direction, UUID destinationWalletId, long grossAmountSats, String externalReference) {
    /** Requires all execution selectors and a positive payer identity. */
    public PaymentRequestLinkExecution {
        Objects.requireNonNull(executionId, "execution id is required");
        Objects.requireNonNull(status, "execution status is required");
        Objects.requireNonNull(rail, "execution rail is required");
        Objects.requireNonNull(direction, "execution direction is required");
        if (userId <= 0L) { throw new IllegalArgumentException("execution owner is required"); }
    }

    /** Confirms the persisted execution is settled and exactly matches the accepted request. */
    /** @param payerUserId authenticated paying account @param expectedId expected execution @param request accepted recipient-owned request @param acceptedAmountSats amount accepted for settlement @throws IllegalStateException when state or any request binding differs */
    public void requireSettledFor(long payerUserId, PaymentExecutionId expectedId,
            PaymentRequestLinkSnapshot request, long acceptedAmountSats) {
        if (status != ExecutionStatus.SETTLED) {
            throw new IllegalStateException("KFE payment request transaction must be settled before marking it paid.");
        }
        if (userId != payerUserId || !executionId.equals(expectedId)
                || rail != PaymentRail.INTERNAL || direction != PaymentDirection.INTERNAL
                || !request.walletId().equals(destinationWalletId) || grossAmountSats != acceptedAmountSats
                || (request.amountSats() != null && grossAmountSats != request.amountSats().longValue())
                || !request.publicId().equals(externalReference)) {
            throw new IllegalStateException("Settled execution does not match the accepted payment request.");
        }
    }

    /** Returns lifecycle metadata while redacting the public request reference. */
    @Override
    public String toString() {
        return "PaymentRequestLinkExecution[executionId=" + executionId + ", userId=" + userId
                + ", status=" + status + ", reference=REDACTED]";
    }
}
