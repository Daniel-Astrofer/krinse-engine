package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;

/** Orchestrates an authorized payment from idempotent preflight through persisted routing. */
public interface SubmitPaymentUseCase {
    /** @param command requested payment and submitted authorization factors @return persisted participant-visible payment projection */
    PaymentExecutionResult submit(SubmitPaymentCommand command);
}
