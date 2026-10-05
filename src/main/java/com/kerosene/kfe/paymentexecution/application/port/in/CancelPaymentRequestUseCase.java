package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentRequestCommand;
import java.util.UUID;

/** Cancels a payment-request aggregate and its eligible related executions. */
public interface CancelPaymentRequestUseCase {
    /** @param command authenticated request cancellation @return cancelled request identifier */
    UUID cancelPaymentRequest(CancelPaymentRequestCommand command);
}
