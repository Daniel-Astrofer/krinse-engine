package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

import java.util.UUID;

/**
 * Stable application message persisted before an external rail is invoked.
 * It captures normalized execution facts so asynchronous delivery does not depend
 * on mutable HTTP request state.
 * @param executionId persisted payment execution identity
 * @param idempotencyKey key used to make external dispatch retries safe
 * @param userId owning account identifier
 * @param rail external payment rail to invoke
 * @param direction direction interpreted by the selected rail
 * @param sourceWalletId optional source wallet identifier
 * @param destinationWalletId optional resolved destination wallet identifier
 * @param amountSats principal amount in integer satoshis
 * @param networkFeeSats network fee in integer satoshis
 * @param totalDebitSats amount plus fees reserved or debited from the source
 * @param externalReference normalized external destination/reference
 * @param memo canonical memo associated with the payment
 * @param quorumProposalHash proposal digest used for settlement quorum validation
 * @param feeRateSatsPerVbyte optional Bitcoin fee quote rate
 * @param feeTargetBlocks optional confirmation target used to derive a Bitcoin fee
 */
public record ScheduleExternalExecutionCommand(
        PaymentExecutionId executionId,
        IdempotencyKey idempotencyKey,
        long userId,
        PaymentRail rail,
        PaymentDirection direction,
        UUID sourceWalletId,
        UUID destinationWalletId,
        long amountSats,
        long networkFeeSats,
        long totalDebitSats,
        String externalReference,
        String memo,
        String quorumProposalHash,
        Long feeRateSatsPerVbyte,
        Integer feeTargetBlocks) {

    /** Validates required execution selectors and prevents negative monetary amounts. */
    public ScheduleExternalExecutionCommand {
        if (executionId == null || idempotencyKey == null || rail == null || direction == null) {
            throw new IllegalArgumentException("execution identity, rail and direction are required");
        }
        if (amountSats < 0L || networkFeeSats < 0L || totalDebitSats < 0L) {
            throw new IllegalArgumentException("execution amounts must be non-negative");
        }
    }

    /** Returns routing metadata while redacting external references and proposal material. */
    @Override
    public String toString() {
        return "ScheduleExternalExecutionCommand[executionId=" + executionId + ", userId=" + userId
                + ", rail=" + rail + ", direction=" + direction + ", references=REDACTED]";
    }
}
