package com.kerosene.kfe.paymentexecution.adapters.in.transaction;

import com.kerosene.kfe.paymentexecution.application.command.SettleInternalPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.SettleInternalPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.usecase.SettleInternalPaymentService;
import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Does not start a standalone settlement: gate, reservation and request link share the caller's commit. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class TransactionalInternalPaymentSettlementAdapter implements SettleInternalPaymentUseCase {
    /** Internal settlement workflow delegated under the mandatory transaction. */
    private final SettleInternalPaymentService service;

    /** Wires the application service whose transaction semantics this adapter enforces. */
    public TransactionalInternalPaymentSettlementAdapter(SettleInternalPaymentService service) {
        this.service = service;
    }

    /** Applies internal settlement as part of the existing caller transaction. */
    @Override
    public PaymentExecutionStatusChanged settle(SettleInternalPaymentCommand command) {
        return service.settle(command);
    }
}
