package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.CompletePaymentSubmissionCommand;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;

/** Completes idempotency and response projection at the end of the owning submit transaction. */
public interface CompletePaymentSubmissionUseCase {
    /** @param command completion identities retained by the current authorized submission @return verified persisted payment projection */
    PaymentExecutionResult complete(CompletePaymentSubmissionCommand command);
}
