package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

import java.util.UUID;

/**
 * Typed immutable facts consumed by the settlement gate; this command carries no authorization
 * outcome. Monetary and risk validation remains the gate's responsibility.
 * @param userId authenticated account submitting the execution
 * @param executionId persisted execution identity under evaluation
 * @param sourceWalletId source wallet when the rail requires a reserve
 * @param idempotencyKey reserved idempotency key associated with the execution
 * @param idempotencyReserved whether reservation has already succeeded
 * @param rail payment rail being settled
 * @param direction inbound or outbound movement
 * @param amountSats requested principal amount in integer satoshis
 * @param networkFeeSats network fee in integer satoshis
 * @param totalDebitSats complete debit to compare against available balance and capacity
 * @param requiresSourceReserve whether the source wallet must be locked and checked
 * @param proposalHash consensus proposal hash whose quorum evidence must be checked
 */
public record PaymentSettlementGateCommand(
        long userId,
        PaymentExecutionId executionId,
        UUID sourceWalletId,
        IdempotencyKey idempotencyKey,
        boolean idempotencyReserved,
        PaymentRail rail,
        PaymentDirection direction,
        long amountSats,
        long networkFeeSats,
        long totalDebitSats,
        boolean requiresSourceReserve,
        String proposalHash) {

    /** Formats routing and amount metadata while redacting proposal/reference details. */
    @Override
    public String toString() {
        return "PaymentSettlementGateCommand[userId=" + userId + ", executionId=" + executionId
                + ", rail=" + rail + ", direction=" + direction + ", amountSats=" + amountSats
                + ", networkFeeSats=" + networkFeeSats + ", totalDebitSats=" + totalDebitSats
                + ", idempotencyReserved=" + idempotencyReserved
                + ", requiresSourceReserve=" + requiresSourceReserve + ", references=REDACTED]";
    }
}
