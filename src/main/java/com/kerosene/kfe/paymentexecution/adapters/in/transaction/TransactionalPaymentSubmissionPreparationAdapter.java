package com.kerosene.kfe.paymentexecution.adapters.in.transaction;

import com.kerosene.kfe.paymentexecution.application.command.PreparePaymentSubmissionCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.PreparePaymentSubmissionUseCase;
import com.kerosene.kfe.paymentexecution.application.result.PreparedPaymentSubmission;
import com.kerosene.kfe.paymentexecution.application.usecase.PreparePaymentSubmissionService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Requires the caller submit transaction to persist validated pricing and preparation state. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class TransactionalPaymentSubmissionPreparationAdapter implements PreparePaymentSubmissionUseCase {
    /** Submission preparation workflow delegated within the caller commit. */
    private final PreparePaymentSubmissionService service;
    /** Wires the application service whose transaction semantics this adapter enforces. */
    public TransactionalPaymentSubmissionPreparationAdapter(PreparePaymentSubmissionService service) { this.service = service; }
    /** Persists the prepared submission state under the caller's mandatory transaction. */
    @Override public PreparedPaymentSubmission prepare(PreparePaymentSubmissionCommand command) { return service.prepare(command); }
}
