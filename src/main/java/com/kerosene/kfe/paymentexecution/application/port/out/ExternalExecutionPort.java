package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.application.result.ExecutionPreparation;
import com.kerosene.kfe.paymentexecution.application.result.ExternalExecutionResult;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;

/** Rail adapter that prepares and dispatches one external operation under an outbox lease. */
public interface ExternalExecutionPort {
    /** Selects this adapter for a durable operation discriminator. */
    /** @param operation outbox operation name @return true when this adapter can execute that operation */
    boolean supports(String operation);
    /** Once dispatch starts, any uncertain send or local ACK failure must produce UNKNOWN, never FINAL_FAILURE. */
    /** @param claim current fenced command lease @param preparation committed provider-dispatch context @return provider-neutral completed, uncertain, retryable, or terminal result */
    ExternalExecutionResult execute(ExecutionClaim claim, ExecutionPreparation preparation);
}
