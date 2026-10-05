package com.kerosene.kfe.paymentexecution.domain.exception;

import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;

public final class InvalidPaymentExecutionTransition extends IllegalStateException {

    public InvalidPaymentExecutionTransition(ExecutionStatus current, ExecutionStatus target) {
        super("Invalid KFE transaction transition from " + current + " to " + target + ".");
    }
}
