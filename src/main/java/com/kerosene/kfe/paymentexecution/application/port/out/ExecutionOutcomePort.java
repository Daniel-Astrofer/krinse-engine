package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;
import java.util.UUID;

/** Each write owns a short transaction and fences by the current claim before changing financial state. */
public interface ExecutionOutcomePort {
    /** Stores uncertain provider evidence for reconciliation while fencing by current lease. */
    /** @param claim current outbox lease @param transactionId payment identity @param reference provider reference, if known @param payload raw provider evidence retained internally @param message bounded operational summary */
    void markUnknown(ExecutionClaim claim, UUID transactionId, String reference, String payload, String message);
    /** Records a failure eligible for later worker retry. */
    /** @param claim current outbox lease @param transactionId payment identity @param code stable retryable failure code @param message bounded failure summary */
    void markRetryableFailure(ExecutionClaim claim, UUID transactionId, String code, String message);
    /** Records a terminal rejection that must not be retried automatically. */
    /** @param claim current outbox lease @param transactionId payment identity @param code stable terminal failure code @param message bounded failure summary */
    void markFinalFailure(ExecutionClaim claim, UUID transactionId, String code, String message);
}
