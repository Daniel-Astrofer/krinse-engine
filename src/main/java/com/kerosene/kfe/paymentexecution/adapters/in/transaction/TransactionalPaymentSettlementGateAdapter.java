package com.kerosene.kfe.paymentexecution.adapters.in.transaction;

import com.kerosene.kfe.paymentexecution.application.command.PaymentSettlementGateCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentSettlementGateUseCase;
import com.kerosene.kfe.paymentexecution.application.result.PaymentSettlementGateResult;
import com.kerosene.kfe.paymentexecution.application.usecase.PaymentSettlementGateService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Requires the caller financial transaction while evaluating the settlement gate. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class TransactionalPaymentSettlementGateAdapter implements PaymentSettlementGateUseCase {
    /** Settlement gate policy and audit workflow. */
    private final PaymentSettlementGateService service;
    /** Wires the application service whose transaction semantics this adapter enforces. */
    public TransactionalPaymentSettlementGateAdapter(PaymentSettlementGateService service) { this.service = service; }
    /** Evaluates the settlement gate within the existing caller transaction. */
    @Override
    public PaymentSettlementGateResult requirePass(PaymentSettlementGateCommand command) { return service.requirePass(command); }
}
