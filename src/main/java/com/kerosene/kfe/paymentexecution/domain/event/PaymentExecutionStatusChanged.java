package com.kerosene.kfe.paymentexecution.domain.event;

import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

/** Domain fact produced after an execution accepts a status transition. */
public record PaymentExecutionStatusChanged(
        PaymentExecutionId executionId,
        ExecutionStatus previousStatus,
        ExecutionStatus currentStatus) {

    public boolean changed() {
        return previousStatus != currentStatus;
    }
}
