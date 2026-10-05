package com.kerosene.kfe.paymentexecution.adapters.in.transaction;

import com.kerosene.kfe.paymentexecution.application.command.CompletePaymentRequestLinkCommand;
import com.kerosene.kfe.paymentexecution.application.command.PreparePaymentRequestLinkCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentRequestLinkUseCase;
import com.kerosene.kfe.paymentexecution.application.result.PreparedPaymentRequestLink;
import com.kerosene.kfe.paymentexecution.application.usecase.PaymentRequestLinkService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

/** Prepare and complete run in one submit transaction, retaining request-before-execution lock order. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class TransactionalPaymentRequestLinkAdapter implements PaymentRequestLinkUseCase {
    /** Payment-request link workflow used by the enclosing submission transaction. */
    private final PaymentRequestLinkService service;
    /** Wires the application service whose transaction semantics this adapter enforces. */
    public TransactionalPaymentRequestLinkAdapter(PaymentRequestLinkService service) { this.service = service; }
    /** Prepares the request link while joining the caller transaction and lock order. */
    @Override
    public Optional<PreparedPaymentRequestLink> prepare(PreparePaymentRequestLinkCommand command) { return service.prepare(command); }
    /** Persists submission completion and projection in the caller transaction. */
    @Override
    public void complete(CompletePaymentRequestLinkCommand command) { service.complete(command); }
}
