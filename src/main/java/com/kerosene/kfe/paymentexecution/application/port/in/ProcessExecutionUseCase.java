package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;

/** Worker entry point; must be invoked without an encompassing database transaction. */
public interface ProcessExecutionUseCase {
    /** Processes a claimed outbox operation without an encompassing transaction. */
    /** @param claim current fenced lease for one durable execution command @throws ExecutionClaimLost when ownership cannot be renewed */
    void process(ExecutionClaim claim);
}
