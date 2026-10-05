package com.kerosene.kfe.paymentexecution.adapters.in.transaction;

import com.kerosene.kfe.paymentexecution.application.command.CompletePaymentSubmissionCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.CompletePaymentSubmissionUseCase;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.application.usecase.CompletePaymentSubmissionService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Requires the caller submission transaction to save completion and its projection atomically. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class TransactionalPaymentSubmissionCompletionAdapter implements CompletePaymentSubmissionUseCase {
    /** Submission completion workflow delegated within the caller commit. */
    private final CompletePaymentSubmissionService service;
    /** Wires the application service whose transaction semantics this adapter enforces. */
    public TransactionalPaymentSubmissionCompletionAdapter(CompletePaymentSubmissionService service) { this.service = service; }
    /** Saves the completion and consumer projection atomically in the caller transaction. */
    @Override public PaymentExecutionResult complete(CompletePaymentSubmissionCommand command) { return service.complete(command); }
}
