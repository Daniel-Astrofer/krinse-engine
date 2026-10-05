package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;

/** Cancels a participant-owned payment or its linked request according to domain hints. */
public interface CancelPaymentUseCase {
    /** @param command authenticated execution cancellation @return refreshed participant-visible execution */
    PaymentExecutionResult cancel(CancelPaymentCommand command);
}
